package com.paycore.auditservice.service;

import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.domain.entity.HashCheckpoint;
import com.paycore.auditservice.hasher.AuditHasher;
import com.paycore.auditservice.repository.AuditRecordRepository;
import com.paycore.auditservice.repository.HashCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCheckpointService {

    private final AuditRecordRepository auditRecordRepository;
    private final HashCheckpointRepository checkpointRepository;
    private final AuditHasher auditHasher;

    @Transactional
    public Optional<HashCheckpoint> createCheckpoint(String publishedReference) {
        Optional<AuditRecord> latestRecordOpt = auditRecordRepository.findTopByOrderBySequenceNumberDesc();
        if (latestRecordOpt.isEmpty()) {
            log.info("No audit records available to checkpoint.");
            return Optional.empty();
        }

        AuditRecord latest = latestRecordOpt.get();
        String checkpointHash = auditHasher.calculateCheckpointHash(latest.getRecordHash(), latest.getSequenceNumber());

        HashCheckpoint checkpoint = HashCheckpoint.builder()
                .upToSequenceNumber(latest.getSequenceNumber())
                .checkpointHash(checkpointHash)
                .publishedReference(publishedReference != null ? publishedReference : "COMPLIANCE_EMAIL_BROADCAST")
                .createdAt(Instant.now())
                .build();

        HashCheckpoint saved = checkpointRepository.save(checkpoint);
        log.info("Created Hash Checkpoint: seq={}, hash={}, reference={}",
                saved.getUpToSequenceNumber(), saved.getCheckpointHash(), saved.getPublishedReference());

        return Optional.of(saved);
    }
}
