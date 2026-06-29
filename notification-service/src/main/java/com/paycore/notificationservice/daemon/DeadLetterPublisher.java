package com.paycore.notificationservice.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.notificationservice.domain.entity.NotificationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.dead-letter-topic:paycore.notification.dead-letter}")
    private String deadLetterTopic;

    public void publishDeadLetter(NotificationLog logEntry) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("notificationId", logEntry.getId());
            payload.put("eventId", logEntry.getEventId());
            payload.put("userId", logEntry.getUserId());
            payload.put("channel", logEntry.getChannel());
            payload.put("templateCode", logEntry.getTemplateCode());
            payload.put("recipientMasked", logEntry.getRecipientMasked());
            payload.put("attemptCount", logEntry.getAttemptCount());
            payload.put("lastError", logEntry.getLastError());
            payload.put("createdAt", logEntry.getCreatedAt().toString());

            String jsonPayload = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(deadLetterTopic, logEntry.getId().toString(), jsonPayload);
            log.info("DEAD_LETTER event published to topic {}: notificationId={}", deadLetterTopic, logEntry.getId());
        } catch (Exception e) {
            log.error("Failed to publish DEAD_LETTER to topic {}: {}", deadLetterTopic, e.getMessage(), e);
        }
    }
}
