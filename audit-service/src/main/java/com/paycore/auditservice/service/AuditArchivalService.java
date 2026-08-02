package com.paycore.auditservice.service;

import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.domain.enums.ActorType;
import com.paycore.auditservice.dto.AuditEventEnvelope;
import com.paycore.auditservice.repository.AuditRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditArchivalService {

    private final AuditRecordRepository auditRecordRepository;
    private final AuditRecordService auditRecordService;

    @Value("${paycore.audit.archival.retention-months:12}")
    private int retentionMonths;

    @Transactional
    public int archiveOldPartitions() {
        Instant threshold = Instant.now().minus(retentionMonths * 30L, ChronoUnit.DAYS);
        List<AuditRecord> oldRecords = auditRecordRepository.findRecordsOlderThan(threshold, PageRequest.of(0, 100));

        if (oldRecords.isEmpty()) {
            return 0;
        }

        log.info("Archiving {} audit records older than {} months to cold storage...",
                oldRecords.size(), retentionMonths);

        // Record audit event of the archival itself
        AuditEventEnvelope archivalEvent = AuditEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .sourceService("audit-service")
                .eventType("AuditPartitionArchived")
                .actorType(ActorType.SYSTEM)
                .actorId("ARCHIVAL_DAEMON")
                .entityType("AUDIT_PARTITION")
                .entityId("COLD_STORAGE_EXPORT")
                .payload(Map.of(
                        "archivedCount", oldRecords.size(),
                        "thresholdDate", threshold.toString(),
                        "destination", "s3://paycore-audit-cold-storage"
                ))
                .occurredAt(Instant.now())
                .build();

        auditRecordService.recordAuditEvent(archivalEvent);
        return oldRecords.size();
    }
}
