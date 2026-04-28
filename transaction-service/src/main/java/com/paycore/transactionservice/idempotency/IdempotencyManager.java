package com.paycore.transactionservice.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.transactionservice.domain.entity.IdempotencyKey;
import com.paycore.transactionservice.domain.enums.IdempotencyStatus;
import com.paycore.transactionservice.exception.IdempotencyConflictException;
import com.paycore.transactionservice.exception.IdempotencyHashMismatchException;
import com.paycore.transactionservice.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-facing 2-Phase Idempotency Manager for transaction-service.
 * <p>
 * Phase 0: Atomic reservation, payload hash validation, snapshot check, and stale lock reclamation.
 * Phase 1: Completion or failure snapshot recording.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyManager {

    private static final Duration STALE_LOCK_THRESHOLD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(24);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Deterministic downstream ledger idempotency key for debit/credit step.
     */
    public static String getLedgerDebitCreditKey(UUID transactionId) {
        return transactionId + ":DEBIT_CREDIT";
    }

    /**
     * Deterministic downstream ledger idempotency key for compensating reversal step.
     */
    public static String getLedgerReversalKey(UUID transactionId) {
        return transactionId + ":REVERSAL";
    }

    /**
     * Phase 0: Reserve key or return cached snapshot. Runs in an isolated transaction.
     *
     * @param idempotencyKey Header-provided client idempotency key
     * @param requestBody    The request DTO
     * @param responseType   Class type of the response for deserializing snapshot
     * @return Cached response if already COMPLETED or FAILED, or null if caller should proceed with Saga
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T startOrCheckIdempotency(String idempotencyKey, Object requestBody, Class<T> responseType) {
        String requestHash = computeHash(requestBody);
        Optional<IdempotencyKey> existingOpt = idempotencyKeyRepository.findById(idempotencyKey);

        if (existingOpt.isPresent()) {
            IdempotencyKey existing = existingOpt.get();

            // 1. Detect key reuse with different payload
            if (!existing.getRequestHash().equals(requestHash)) {
                log.warn("Idempotency key {} reused with different payload hash", idempotencyKey);
                throw new IdempotencyHashMismatchException("Idempotency key reused with different payload");
            }

            // 2. Return cached snapshot if COMPLETED or FAILED
            if (existing.getStatus() == IdempotencyStatus.COMPLETED || existing.getStatus() == IdempotencyStatus.FAILED) {
                log.info("Idempotent hit: returning cached snapshot for key={}, status={}", idempotencyKey, existing.getStatus());
                if (existing.getResponseSnapshot() != null) {
                    try {
                        return objectMapper.readValue(existing.getResponseSnapshot(), responseType);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize idempotency snapshot for key {}", idempotencyKey, e);
                    }
                }
                return null;
            }

            // 3. Status is PROCESSING: Check if stale (> 30s)
            Instant now = Instant.now();
            if (existing.getUpdatedAt().isBefore(now.minus(STALE_LOCK_THRESHOLD))) {
                log.warn("Reclaiming stale PROCESSING idempotency key: {} (last updated {}s ago)",
                        idempotencyKey, Duration.between(existing.getUpdatedAt(), now).toSeconds());
                existing.setUpdatedAt(now);
                idempotencyKeyRepository.save(existing);
                return null; // Reclaim lock and allow processing to continue
            } else {
                log.warn("Concurrent request detected for active idempotency key: {}", idempotencyKey);
                throw new IdempotencyConflictException("A request with this idempotency key is currently processing");
            }
        }

        // 4. New key -> insert row with status=PROCESSING
        IdempotencyKey newKey = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.PROCESSING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .expiresAt(Instant.now().plus(DEFAULT_EXPIRATION))
                .build();

        idempotencyKeyRepository.save(newKey);
        return null;
    }

    /**
     * Mark idempotency key as COMPLETED with response snapshot in an isolated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeIdempotency(String idempotencyKey, UUID transactionId, Object response) {
        idempotencyKeyRepository.findById(idempotencyKey).ifPresent(key -> {
            key.setStatus(IdempotencyStatus.COMPLETED);
            key.setTransactionId(transactionId);
            key.setUpdatedAt(Instant.now());
            try {
                key.setResponseSnapshot(objectMapper.writeValueAsString(response));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize response snapshot for key {}", idempotencyKey, e);
            }
            idempotencyKeyRepository.save(key);
        });
    }

    /**
     * Mark idempotency key as FAILED with error snapshot in an isolated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failIdempotency(String idempotencyKey, UUID transactionId, Object failureResponse) {
        idempotencyKeyRepository.findById(idempotencyKey).ifPresent(key -> {
            key.setStatus(IdempotencyStatus.FAILED);
            key.setTransactionId(transactionId);
            key.setUpdatedAt(Instant.now());
            try {
                key.setResponseSnapshot(objectMapper.writeValueAsString(failureResponse));
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize failure snapshot for key {}", idempotencyKey, e);
            }
            idempotencyKeyRepository.save(key);
        });
    }

    public String computeHash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Error computing payload hash", e);
        }
    }
}
