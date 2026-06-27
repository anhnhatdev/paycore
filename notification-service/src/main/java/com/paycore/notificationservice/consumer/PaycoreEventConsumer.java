package com.paycore.notificationservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.notificationservice.dto.KafkaEventEnvelope;
import com.paycore.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!test")
public class PaycoreEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    "${paycore.kafka.topics.transaction-events:paycore.transaction-events}",
                    "${paycore.kafka.topics.gateway-events:paycore.gateway-events}",
                    "${paycore.kafka.topics.account-events:paycore.account-events}"
            },
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEvent(
            @Payload String message,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            Acknowledgment acknowledgment
    ) {
        log.info("Kafka message received on topic {}: {}", topic, message);
        try {
            KafkaEventEnvelope envelope = parseEnvelope(message, topic);
            notificationService.processEvent(envelope);
        } catch (Exception e) {
            log.error("Error processing Kafka message on topic {}: {}", topic, e.getMessage(), e);
        } finally {
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        }
    }

    private KafkaEventEnvelope parseEnvelope(String message, String topic) {
        try {
            JsonNode root = objectMapper.readTree(message);

            UUID eventId = root.has("eventId") ? UUID.fromString(root.get("eventId").asText())
                    : (root.has("id") ? UUID.fromString(root.get("id").asText()) : UUID.randomUUID());

            String eventType = root.has("eventType") ? root.get("eventType").asText()
                    : (root.has("type") ? root.get("type").asText() : "UNKNOWN_EVENT");

            String sourceService = root.has("sourceService") ? root.get("sourceService").asText()
                    : (topic != null ? topic : "kafka-broker");

            UUID userId = null;
            if (root.has("userId") && !root.get("userId").isNull()) {
                try {
                    userId = UUID.fromString(root.get("userId").asText());
                } catch (Exception ignored) {}
            }

            Map<String, Object> payloadMap = new HashMap<>();
            if (root.has("payload") && root.get("payload").isObject()) {
                JsonNode payloadNode = root.get("payload");
                Iterator<Map.Entry<String, JsonNode>> fields = payloadNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    payloadMap.put(field.getKey(), field.getValue().asText());
                }
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    payloadMap.put(field.getKey(), field.getValue().asText());
                }
            }

            return KafkaEventEnvelope.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .sourceService(sourceService)
                    .userId(userId)
                    .payload(payloadMap)
                    .timestamp(Instant.now())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse JSON envelope, wrapping raw message: {}", e.getMessage());
            return KafkaEventEnvelope.builder()
                    .eventId(UUID.randomUUID())
                    .eventType("RAW_EVENT")
                    .sourceService(topic)
                    .payload(Map.of("raw", message))
                    .timestamp(Instant.now())
                    .build();
        }
    }
}
