package com.paycore.auditservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.auditservice.domain.enums.ActorType;
import com.paycore.auditservice.dto.AuditEventEnvelope;
import com.paycore.auditservice.service.AuditRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class AuditKafkaConsumer {

    private final AuditRecordService auditRecordService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    "${paycore.kafka.topics.transaction-events:paycore.transaction-events}",
                    "${paycore.kafka.topics.gateway-events:paycore.gateway-events}",
                    "${paycore.kafka.topics.account-events:paycore.account-events}",
                    "${paycore.kafka.topics.fraud-events:paycore.fraud-events}"
            },
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeAuditEvent(
            @Payload String message,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            Acknowledgment acknowledgment
    ) {
        log.info("Audit consumer received message on topic {}: {}", topic, message);
        try {
            AuditEventEnvelope envelope = parseEnvelope(message, topic);
            auditRecordService.recordAuditEvent(envelope);
        } catch (Exception e) {
            log.error("Error processing audit Kafka message: {}", e.getMessage(), e);
        } finally {
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        }
    }

    private AuditEventEnvelope parseEnvelope(String message, String topic) {
        try {
            JsonNode root = objectMapper.readTree(message);

            UUID eventId = root.has("eventId") ? UUID.fromString(root.get("eventId").asText())
                    : (root.has("id") ? UUID.fromString(root.get("id").asText()) : UUID.randomUUID());

            String eventType = root.has("eventType") ? root.get("eventType").asText()
                    : (root.has("type") ? root.get("type").asText() : "UNKNOWN_EVENT");

            String sourceService = root.has("sourceService") ? root.get("sourceService").asText()
                    : (topic != null ? topic : "paycore");

            String actorId = root.has("actorId") ? root.get("actorId").asText()
                    : (root.has("userId") ? root.get("userId").asText() : null);

            ActorType actorType = actorId != null ? ActorType.USER : ActorType.SYSTEM;

            String entityType = root.has("entityType") ? root.get("entityType").asText() : "TRANSACTION";
            String entityId = root.has("entityId") ? root.get("entityId").asText()
                    : (root.has("transactionId") ? root.get("transactionId").asText() : null);

            Instant occurredAt = root.has("occurredAt")
                    ? Instant.parse(root.get("occurredAt").asText())
                    : Instant.now();

            String rawPayload = root.has("payload") ? root.get("payload").toString() : message;

            return AuditEventEnvelope.builder()
                    .eventId(eventId)
                    .sourceService(sourceService)
                    .eventType(eventType)
                    .actorType(actorType)
                    .actorId(actorId)
                    .entityType(entityType)
                    .entityId(entityId)
                    .rawPayloadJson(rawPayload)
                    .occurredAt(occurredAt)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse JSON envelope in audit consumer, falling back to raw wrapping: {}", e.getMessage());
            return AuditEventEnvelope.builder()
                    .eventId(UUID.randomUUID())
                    .sourceService(topic)
                    .eventType("RAW_EVENT")
                    .actorType(ActorType.SYSTEM)
                    .rawPayloadJson(message)
                    .occurredAt(Instant.now())
                    .build();
        }
    }
}
