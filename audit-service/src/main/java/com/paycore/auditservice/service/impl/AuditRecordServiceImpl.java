package com.paycore.auditservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.domain.entity.ProcessedEvent;
import com.paycore.auditservice.domain.enums.ActorType;
import com.paycore.auditservice.dto.AuditEventEnvelope;
import com.paycore.auditservice.hasher.AuditHasher;
import com.paycore.auditservice.redactor.PayloadRedactor;
import com.paycore.auditservice.repository.AuditRecordRepository;
import com.paycore.auditservice.repository.ProcessedEventRepository;
import com.paycore.auditservice.service.AuditRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditRecordServiceImpl implements AuditRecordService {

    private final AuditRecordRepository auditRecordRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PayloadRedactor payloadRedactor;
    private final AuditHasher auditHasher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public synchronized Optional<AuditRecord> recordAuditEvent(AuditEventEnvelope event) {
        if (event == null || event.getEventId() == null) {
            log.warn("Discarding invalid or null audit event");
            return Optional.empty();
        }

        UUID eventId = event.getEventId();

        // 1. Check duplicate processing
        if (processedEventRepository.existsById(eventId)) {
            log.info("DEDUP HIT: Audit event {} already processed. Skipping duplicate.", eventId);
            return Optional.empty();
        }

        // 2. Mark event as processed
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now())
                .build());

        // 3. Prepare payload string & redact sensitive data
        String rawJson = event.getRawPayloadJson();
        if ((rawJson == null || rawJson.isBlank()) && event.getPayload() != null) {
            try {
                rawJson = objectMapper.writeValueAsString(event.getPayload());
            } catch (Exception e) {
                rawJson = "{}";
            }
        }
        String sanitizedPayload = payloadRedactor.redactPayload(
                rawJson != null ? rawJson : "{}",
                event.getSourceService() != null ? event.getSourceService() : "UNKNOWN",
                event.getEventType() != null ? event.getEventType() : "UNKNOWN"
        );

        // 4. Retrieve latest record for sequence & prev_hash chaining
        Optional<AuditRecord> latestRecordOpt = auditRecordRepository.findTopByOrderBySequenceNumberDesc();
        Long nextSequence = latestRecordOpt.map(r -> r.getSequenceNumber() + 1).orElse(1L);
        String prevHash = latestRecordOpt.map(AuditRecord::getRecordHash).orElse(AuditHasher.GENESIS_HASH);

        Instant occurredAt = event.getOccurredAt() != null ? event.getOccurredAt() : Instant.now();

        // 5. Calculate SHA-256 chained hash
        String recordHash = auditHasher.calculateRecordHash(
                prevHash,
                eventId,
                sanitizedPayload,
                occurredAt,
                nextSequence
        );

        AuditRecord auditRecord = AuditRecord.builder()
                .sequenceNumber(nextSequence)
                .eventId(eventId)
                .sourceService(event.getSourceService() != null ? event.getSourceService() : "paycore")
                .eventType(event.getEventType() != null ? event.getEventType() : "GENERIC_EVENT")
                .actorType(event.getActorType() != null ? event.getActorType() : ActorType.SYSTEM)
                .actorId(event.getActorId())
                .entityType(event.getEntityType() != null ? event.getEntityType() : "GENERIC")
                .entityId(event.getEntityId())
                .payload(sanitizedPayload)
                .recordHash(recordHash)
                .prevHash(prevHash)
                .occurredAt(occurredAt)
                .recordedAt(Instant.now())
                .build();

        AuditRecord saved = auditRecordRepository.save(auditRecord);
        log.info("Recorded AuditRecord: seq={}, hash={}, eventId={}, eventType={}",
                saved.getSequenceNumber(), saved.getRecordHash().substring(0, 16) + "...", eventId, saved.getEventType());

        return Optional.of(saved);
    }
}
