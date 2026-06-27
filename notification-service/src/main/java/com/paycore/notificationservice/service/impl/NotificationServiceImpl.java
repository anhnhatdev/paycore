package com.paycore.notificationservice.service.impl;

import com.paycore.notificationservice.client.AccountClient;
import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.domain.entity.NotificationPreference;
import com.paycore.notificationservice.domain.entity.ProcessedEvent;
import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.domain.enums.NotificationEventType;
import com.paycore.notificationservice.domain.enums.NotificationStatus;
import com.paycore.notificationservice.dto.KafkaEventEnvelope;
import com.paycore.notificationservice.dto.RenderedMessage;
import com.paycore.notificationservice.dto.UserContactDto;
import com.paycore.notificationservice.exception.NotificationDeliveryException;
import com.paycore.notificationservice.provider.NotificationProvider;
import com.paycore.notificationservice.provider.NotificationProviderRegistry;
import com.paycore.notificationservice.repository.NotificationLogRepository;
import com.paycore.notificationservice.repository.NotificationPreferenceRepository;
import com.paycore.notificationservice.repository.ProcessedEventRepository;
import com.paycore.notificationservice.service.NotificationService;
import com.paycore.notificationservice.template.NotificationTemplateEngine;
import com.paycore.notificationservice.util.RecipientMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationProviderRegistry providerRegistry;
    private final AccountClient accountClient;

    @Override
    public boolean processEvent(KafkaEventEnvelope event) {
        if (event == null || event.getEventId() == null) {
            log.warn("Discarding invalid or null Kafka event: {}", event);
            return true;
        }

        UUID eventId = event.getEventId();
        String eventType = event.getEventType() != null ? event.getEventType() : "UNKNOWN_EVENT";
        String sourceService = event.getSourceService() != null ? event.getSourceService() : "UNKNOWN_SERVICE";
        UUID userId = event.getUserId();
        Map<String, Object> payload = event.getPayload() != null ? event.getPayload() : Collections.emptyMap();

        // If userId is not directly in the envelope, extract from payload
        if (userId == null && payload.containsKey("userId")) {
            try {
                userId = UUID.fromString(payload.get("userId").toString());
            } catch (Exception ignored) {}
        }

        log.info("Processing Kafka event: eventId={}, eventType={}, sourceService={}, userId={}",
                eventId, eventType, sourceService, userId);

        // STEP 2: CHECK DEDUP BEFORE ANYTHING ELSE
        if (processedEventRepository.existsById(eventId)) {
            log.info("DEDUP HIT: Event {} already processed. Skipping duplicate delivery.", eventId);
            return true;
        }

        // Resolve user contact info real-time (default channel = EMAIL)
        NotificationChannel channel = NotificationChannel.EMAIL;
        String rawRecipient = "user@paycore.com";
        String maskedRecipient = "***";

        if (userId != null) {
            try {
                UserContactDto contact = accountClient.getUserContact(userId);
                if (contact != null && contact.getEmail() != null) {
                    rawRecipient = contact.getEmail();
                    maskedRecipient = RecipientMasker.maskEmail(rawRecipient);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve contact for userId {}: {}", userId, e.getMessage());
            }
        }

        // STEP 3: ATOMIC INSERT of processed_events + notifications (status=PENDING)
        NotificationLog notificationLog;
        try {
            notificationLog = recordInitialEventAndPendingLog(
                    eventId,
                    eventType,
                    sourceService,
                    userId != null ? userId : UUID.randomUUID(),
                    channel,
                    templateEngine.mapTemplateCode(eventType, channel),
                    maskedRecipient
            );
        } catch (Exception e) {
            // Concurrent duplicate insertion attempt caught by DB unique PK
            log.warn("Concurrent duplicate event detected for eventId {}: {}", eventId, e.getMessage());
            return true;
        }

        // STEP 4: Check notification_preferences (Honor opt-out EXCEPT for non-optional security events)
        boolean isNonOptional = NotificationEventType.isNonOptional(eventType);
        if (!isNonOptional && userId != null) {
            Optional<NotificationPreference> pref = preferenceRepository
                    .findByIdUserIdAndIdEventTypeAndIdChannel(userId, eventType, channel);
            if (pref.isPresent() && !pref.get().isEnabled()) {
                log.info("Event {} skipped due to user preference opt-out (userId={}, eventType={})",
                        eventId, userId, eventType);
                notificationLog.setStatus(NotificationStatus.SKIPPED_BY_PREFERENCE);
                notificationLogRepository.save(notificationLog);
                return true;
            }
        } else if (isNonOptional) {
            log.info("Event {} is NON-OPTIONAL security alert ({}), user preference check bypassed.",
                    eventId, eventType);
        }

        // STEP 6: Render template
        RenderedMessage message = templateEngine.render(
                eventType,
                channel,
                rawRecipient,
                maskedRecipient,
                payload
        );
        message.setNotificationId(notificationLog.getId());
        message.setUserId(notificationLog.getUserId());

        // STEP 7 & 8: Dispatch to provider and update status
        try {
            NotificationProvider provider = providerRegistry.getProvider(channel)
                    .orElseThrow(() -> new NotificationDeliveryException("No provider registered for channel: " + channel));
            provider.send(message);

            notificationLog.setStatus(NotificationStatus.SENT);
            notificationLog.setSentAt(Instant.now());
            notificationLog.setAttemptCount(notificationLog.getAttemptCount() + 1);
            notificationLogRepository.save(notificationLog);
            log.info("Notification {} successfully sent to recipient={}", notificationLog.getId(), maskedRecipient);
        } catch (Exception e) {
            log.error("Failed to deliver notification {} on first attempt: {}", notificationLog.getId(), e.getMessage());
            notificationLog.setStatus(NotificationStatus.FAILED);
            notificationLog.setAttemptCount(notificationLog.getAttemptCount() + 1);
            notificationLog.setLastError(e.getMessage() != null && e.getMessage().length() > 490
                    ? e.getMessage().substring(0, 490) : e.getMessage());
            notificationLogRepository.save(notificationLog);
        }

        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationLog recordInitialEventAndPendingLog(
            UUID eventId,
            String eventType,
            String sourceService,
            UUID userId,
            NotificationChannel channel,
            String templateCode,
            String recipientMasked
    ) {
        ProcessedEvent processedEvent = ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .sourceService(sourceService)
                .processedAt(Instant.now())
                .build();
        processedEventRepository.save(processedEvent);

        NotificationLog logEntry = NotificationLog.builder()
                .eventId(eventId)
                .userId(userId)
                .channel(channel)
                .templateCode(templateCode)
                .recipientMasked(recipientMasked)
                .status(NotificationStatus.PENDING)
                .attemptCount(0)
                .createdAt(Instant.now())
                .build();
        return notificationLogRepository.save(logEntry);
    }

    @Override
    @Transactional(noRollbackFor = Exception.class)
    public void dispatchNotification(NotificationLog logEntry, String plainRecipient) {
        if (logEntry == null) return;

        NotificationChannel channel = logEntry.getChannel();
        NotificationProvider provider = providerRegistry.getProvider(channel)
                .orElseThrow(() -> new NotificationDeliveryException("No provider for channel: " + channel));

        RenderedMessage message = RenderedMessage.builder()
                .notificationId(logEntry.getId())
                .userId(logEntry.getUserId())
                .channel(channel)
                .recipient(plainRecipient != null ? plainRecipient : "user@paycore.com")
                .recipientMasked(logEntry.getRecipientMasked())
                .templateCode(logEntry.getTemplateCode())
                .subject("[PayCore] Thông báo tự động")
                .body("Thông báo cập nhật giao dịch hoặc tài khoản của bạn.")
                .build();

        try {
            provider.send(message);
            logEntry.setStatus(NotificationStatus.SENT);
            logEntry.setSentAt(Instant.now());
            logEntry.setAttemptCount(logEntry.getAttemptCount() + 1);
            logEntry.setLastError(null);
        } catch (Exception e) {
            logEntry.setAttemptCount(logEntry.getAttemptCount() + 1);
            logEntry.setLastError(e.getMessage() != null && e.getMessage().length() > 490
                    ? e.getMessage().substring(0, 490) : e.getMessage());
            if (logEntry.getAttemptCount() >= 3) {
                logEntry.setStatus(NotificationStatus.DEAD_LETTER);
            } else {
                logEntry.setStatus(NotificationStatus.FAILED);
            }
            throw e;
        } finally {
            notificationLogRepository.save(logEntry);
        }
    }
}
