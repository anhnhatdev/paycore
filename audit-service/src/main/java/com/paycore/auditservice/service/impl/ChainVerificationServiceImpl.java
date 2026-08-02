package com.paycore.auditservice.service.impl;

import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.dto.ChainVerificationResult;
import com.paycore.auditservice.hasher.AuditHasher;
import com.paycore.auditservice.repository.AuditRecordRepository;
import com.paycore.auditservice.service.ChainVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainVerificationServiceImpl implements ChainVerificationService {

    private final AuditRecordRepository auditRecordRepository;
    private final AuditHasher auditHasher;

    @Override
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChain(Long fromSeq, Long toSeq) {
        long startSeq = (fromSeq != null && fromSeq > 0) ? fromSeq : 1L;
        long endSeq = (toSeq != null && toSeq >= startSeq) ? toSeq : Long.MAX_VALUE;

        List<AuditRecord> records = auditRecordRepository.findBySequenceNumberBetweenOrderBySequenceNumberAsc(startSeq, endSeq);

        if (records.isEmpty()) {
            return ChainVerificationResult.builder()
                    .valid(true)
                    .verifiedRecordsCount(0L)
                    .message("No records found in sequence range [" + startSeq + " to " + endSeq + "]")
                    .build();
        }

        long verifiedCount = 0;
        String expectedPrevHash = null;

        for (AuditRecord record : records) {
            verifiedCount++;

            // 1. Verify prev_hash link
            if (expectedPrevHash != null && !record.getPrevHash().equals(expectedPrevHash)) {
                log.error("🚨 HASH CHAIN BROKEN: Sequence {} prev_hash mismatch. Expected {}, found {}",
                        record.getSequenceNumber(), expectedPrevHash, record.getPrevHash());
                return ChainVerificationResult.builder()
                        .valid(false)
                        .verifiedRecordsCount(verifiedCount)
                        .corruptedSequenceNumber(record.getSequenceNumber())
                        .expectedHash(expectedPrevHash)
                        .actualHash(record.getPrevHash())
                        .message("Chain broken: prev_hash mismatch at sequence " + record.getSequenceNumber())
                        .build();
            }

            // 2. Re-calculate SHA-256 record_hash from fields
            String recalculatedHash = auditHasher.calculateRecordHash(
                    record.getPrevHash(),
                    record.getEventId(),
                    record.getPayload(),
                    record.getOccurredAt(),
                    record.getSequenceNumber()
            );

            if (!recalculatedHash.equals(record.getRecordHash())) {
                log.error("🚨 TAMPER DETECTED: Sequence {} content modified! Expected hash {}, calculated hash {}",
                        record.getSequenceNumber(), record.getRecordHash(), recalculatedHash);
                return ChainVerificationResult.builder()
                        .valid(false)
                        .verifiedRecordsCount(verifiedCount)
                        .corruptedSequenceNumber(record.getSequenceNumber())
                        .expectedHash(record.getRecordHash())
                        .actualHash(recalculatedHash)
                        .message("Tamper detected: payload or metadata altered at sequence " + record.getSequenceNumber())
                        .build();
            }

            expectedPrevHash = record.getRecordHash();
        }

        log.info("Chain verification successful: {} records verified from seq {} to {}",
                verifiedCount, startSeq, records.get(records.size() - 1).getSequenceNumber());

        return ChainVerificationResult.builder()
                .valid(true)
                .verifiedRecordsCount(verifiedCount)
                .message("Hash chain fully verified and tamper-free.")
                .build();
    }
}
