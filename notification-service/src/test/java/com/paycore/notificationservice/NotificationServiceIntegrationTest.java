package com.paycore.notificationservice;

import com.paycore.notificationservice.client.AccountClient;
import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.domain.entity.ProcessedEvent;
import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.domain.enums.NotificationStatus;
import com.paycore.notificationservice.dto.KafkaEventEnvelope;
import com.paycore.notificationservice.dto.UserContactDto;
import com.paycore.notificationservice.provider.EmailNotificationProvider;
import com.paycore.notificationservice.repository.NotificationLogRepository;
import com.paycore.notificationservice.repository.NotificationPreferenceRepository;
import com.paycore.notificationservice.repository.ProcessedEventRepository;
import com.paycore.notificationservice.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private NotificationLogRepository notificationLogRepository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private EmailNotificationProvider emailNotificationProvider;

    @MockBean
    private AccountClient accountClient;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        notificationLogRepository.deleteAll();
        processedEventRepository.deleteAll();
        preferenceRepository.deleteAll();
        emailNotificationProvider.setSimulateFailure(false);

        when(accountClient.getUserContact(any())).thenReturn(UserContactDto.builder()
                .userId(UUID.randomUUID())
                .email("john.doe@example.com")
                .phoneNumber("0901234567")
                .fullName("John Doe")
                .build());
    }

    @AfterEach
    void tearDown() {
        emailNotificationProvider.setSimulateFailure(false);
        notificationLogRepository.deleteAll();
        processedEventRepository.deleteAll();
        preferenceRepository.deleteAll();
    }

    // ─── Test 1: Idempotency — duplicate events are delivered exactly once ────

    @Test
    @DisplayName("TEST-1: Duplicate Kafka events result in exactly ONE notification delivered (idempotency)")
    void processEvent_DuplicateKafkaMessage_DeliveredExactlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        KafkaEventEnvelope envelope = buildEnvelope(eventId, "TransactionCompleted", userId);

        boolean first = notificationService.processEvent(envelope);
        boolean second = notificationService.processEvent(envelope);

        assertThat(first).isTrue();
        assertThat(second).isTrue();

        long sentCount = notificationLogRepository.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.SENT)
                .count();

        long processedEventsCount = processedEventRepository.findAll().stream()
                .filter(e -> e.getEventId().equals(eventId))
                .count();

        assertThat(sentCount).isEqualTo(1);
        assertThat(processedEventsCount).isEqualTo(1);
    }

    // ─── Test 2: User preference opt-out → creates SKIPPED_BY_PREFERENCE ─────

    @Test
    @DisplayName("TEST-2: User opt-out creates SKIPPED_BY_PREFERENCE notification record")
    void processEvent_UserOptedOut_StatusSkippedByPreference() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        com.paycore.notificationservice.domain.entity.NotificationPreferenceId prefId =
                com.paycore.notificationservice.domain.entity.NotificationPreferenceId.builder()
                        .userId(userId)
                        .eventType("TransactionCompleted")
                        .channel(NotificationChannel.EMAIL)
                        .build();

        preferenceRepository.save(
                com.paycore.notificationservice.domain.entity.NotificationPreference.builder()
                        .id(prefId)
                        .enabled(false)
                        .build()
        );

        KafkaEventEnvelope envelope = buildEnvelope(eventId, "TransactionCompleted", userId);
        notificationService.processEvent(envelope);

        NotificationLog log = notificationLogRepository.findByEventId(eventId).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SKIPPED_BY_PREFERENCE);
    }

    // ─── Test 3: Security bypass — AccountFrozen always delivered even opted out

    @Test
    @DisplayName("TEST-3: AccountFrozen is delivered even when user opted out (non-optional security bypass)")
    void processEvent_AccountFrozenWithOptOut_StillDelivered() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        com.paycore.notificationservice.domain.entity.NotificationPreferenceId prefId =
                com.paycore.notificationservice.domain.entity.NotificationPreferenceId.builder()
                        .userId(userId)
                        .eventType("AccountFrozen")
                        .channel(NotificationChannel.EMAIL)
                        .build();

        preferenceRepository.save(
                com.paycore.notificationservice.domain.entity.NotificationPreference.builder()
                        .id(prefId)
                        .enabled(false)
                        .build()
        );

        KafkaEventEnvelope envelope = buildEnvelope(eventId, "AccountFrozen", userId);
        notificationService.processEvent(envelope);

        NotificationLog log = notificationLogRepository.findByEventId(eventId).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // ─── Test 4: Provider failure transitions to FAILED status ───────────────

    @Test
    @DisplayName("TEST-4: Transient provider failure records FAILED status with error details")
    void processEvent_ProviderFailure_RecordedAsFailed() {
        emailNotificationProvider.setSimulateFailure(true);

        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        KafkaEventEnvelope envelope = buildEnvelope(eventId, "TransactionCompleted", userId);
        notificationService.processEvent(envelope);

        NotificationLog log = notificationLogRepository.findByEventId(eventId).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(log.getLastError()).isNotBlank();
        assertThat(log.getAttemptCount()).isEqualTo(1);
    }

    // ─── Test 5: Permanent failure → DEAD_LETTER after max retries ───────────

    @Test
    @DisplayName("TEST-5: Notification exceeding max attempts transitions to DEAD_LETTER status")
    void dispatchNotification_MaxAttemptsExceeded_TransitionsToDEAD_LETTER() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        emailNotificationProvider.setSimulateFailure(true);

        NotificationLog logEntry = notificationLogRepository.save(NotificationLog.builder()
                .eventId(eventId)
                .userId(userId)
                .channel(NotificationChannel.EMAIL)
                .templateCode("TEST_DLQ")
                .recipientMasked("j*****e@example.com")
                .status(NotificationStatus.FAILED)
                .attemptCount(2)
                .createdAt(Instant.now())
                .build());

        try {
            notificationService.dispatchNotification(logEntry, "john.doe@example.com");
        } catch (Exception ignored) {}

        NotificationLog updated = notificationLogRepository.findById(logEntry.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(updated.getAttemptCount()).isEqualTo(3);
    }

    // ─── Test 6: Stuck PENDING recovery — stuck logs are retried ─────────────

    @Test
    @DisplayName("TEST-6: Stuck PENDING notifications older than threshold are recovered by daemon query")
    void findStuckPendingNotifications_OlderThanThreshold_ReturnsStuckLogs() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        NotificationLog stuckLog = notificationLogRepository.save(NotificationLog.builder()
                .eventId(eventId)
                .userId(userId)
                .channel(NotificationChannel.EMAIL)
                .templateCode("STUCK_TEST")
                .recipientMasked("j*****e@example.com")
                .status(NotificationStatus.PENDING)
                .attemptCount(0)
                .createdAt(Instant.now().minusSeconds(600))
                .build());

        Instant stuckThreshold = Instant.now().minusSeconds(300);
        var stuck = notificationLogRepository.findStuckPendingNotifications(
                stuckThreshold,
                org.springframework.data.domain.PageRequest.of(0, 50)
        );

        assertThat(stuck).isNotEmpty();
        assertThat(stuck.get(0).getId()).isEqualTo(stuckLog.getId());
        assertThat(stuck.get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    // ─── Test 7: Privacy masking — recipient is always masked in DB ───────────

    @Test
    @DisplayName("TEST-7: Database only stores masked recipient — real email never persisted")
    void processEvent_RecipientMaskedInDatabase_RealEmailNotStored() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        KafkaEventEnvelope envelope = buildEnvelope(eventId, "TransactionCompleted", userId);
        notificationService.processEvent(envelope);

        NotificationLog log = notificationLogRepository.findByEventId(eventId).orElseThrow();
        String maskedRecipient = log.getRecipientMasked();

        assertThat(maskedRecipient).doesNotContain("john.doe");
        assertThat(maskedRecipient).contains("@example.com");
        assertThat(maskedRecipient).matches(".*\\*.*@.*\\..*");
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private KafkaEventEnvelope buildEnvelope(UUID eventId, String eventType, UUID userId) {
        return KafkaEventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .sourceService("test-service")
                .userId(userId)
                .payload(Map.of(
                        "transactionId", UUID.randomUUID().toString(),
                        "amount", "1000000.00",
                        "currency", "VND"
                ))
                .timestamp(Instant.now())
                .build();
    }
}
