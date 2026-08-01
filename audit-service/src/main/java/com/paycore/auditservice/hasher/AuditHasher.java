package com.paycore.auditservice.hasher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
public class AuditHasher {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Calculates the SHA-256 hash for a single audit record chained to prevHash.
     * record_hash = SHA256(prev_hash + event_id + payload + occurred_at + sequence_number)
     */
    public String calculateRecordHash(
            String prevHash,
            UUID eventId,
            String payload,
            Instant occurredAt,
            Long sequenceNumber
    ) {
        String effectivePrevHash = (prevHash != null && !prevHash.isBlank()) ? prevHash : GENESIS_HASH;
        String rawData = effectivePrevHash +
                "|" + (eventId != null ? eventId.toString() : "") +
                "|" + (payload != null ? payload : "{}") +
                "|" + (occurredAt != null ? occurredAt.toEpochMilli() : 0L) +
                "|" + (sequenceNumber != null ? sequenceNumber.toString() : "0");

        return sha256(rawData);
    }

    /**
     * Computes cumulative SHA-256 checkpoint hash for a batch of records.
     */
    public String calculateCheckpointHash(String lastRecordHash, Long upToSequenceNumber) {
        String data = "CHECKPOINT:" + upToSequenceNumber + ":" + lastRecordHash;
        return sha256(data);
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
