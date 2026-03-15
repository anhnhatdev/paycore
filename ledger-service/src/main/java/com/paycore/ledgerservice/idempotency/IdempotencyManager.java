package com.paycore.ledgerservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.ledgerservice.domain.entity.IdempotencyKey;
import com.paycore.ledgerservice.domain.entity.IdempotencyStatus;
import com.paycore.ledgerservice.exception.IdempotencyConflictException;
import com.paycore.ledgerservice.exception.IdempotencyPayloadMismatchException;
import com.paycore.ledgerservice.repository.IdempotencyKeyRepository;
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

/**
 * 2-Phase Idempotency Manager.
 * Separates the idempotency state tracking transaction (Phase 0 / Failure handling)
 * from the main business ledger transaction (Phase 1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyManager {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    private static final long STALE_TIMEOUT_SECONDS = 30;
    private static final long TTL_HOURS = 24;

    /**
     * Phase 0: Check existing idempotency key or register a new PROCESSING lock.
     * Committed in a dedicated, isolated transaction (REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencySnapshot> startOrCheckIdempotency(String idempotencyKey, Object requestPayload) {
        String requestHash = computeHash(requestPayload);
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findById(idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyKey record = existing.get();

            // 1. Verify payload hash match
            if (!record.getRequestHash().equals(requestHash)) {
                log.warn("Idempotency key reused with different payload: key={}", idempotencyKey);
                throw new IdempotencyPayloadMismatchException("Idempotency key reused with different payload");
            }

            // 2. Return cached snapshot if COMPLETED or FAILED
            if (record.getStatus() == IdempotencyStatus.COMPLETED || record.getStatus() == IdempotencyStatus.FAILED) {
                log.info("Idempotent hit: returning cached snapshot for key={}, status={}", idempotencyKey, record.getStatus());
                return Optional.of(new IdempotencySnapshot(record.getResponseSnapshot(), record.getStatus()));
            }

            // 3. Handle PROCESSING state
            long secondsSinceLastUpdate = Duration.between(record.getUpdatedAt(), Instant.now()).getSeconds();
            if (secondsSinceLastUpdate < STALE_TIMEOUT_SECONDS) {
                log.warn("Concurrent request in progress for idempotency key: {}", idempotencyKey);
                throw new IdempotencyConflictException("Concurrent request in progress for idempotency key: " + idempotencyKey);
            }

            // 4. Stale recovery (>30s without completion)
            log.warn("Reclaiming stale PROCESSING idempotency key: {} (last updated {}s ago)", idempotencyKey, secondsSinceLastUpdate);
            record.setUpdatedAt(Instant.now());
            idempotencyKeyRepository.save(record);
            return Optional.empty();
        }

        // 5. Register new idempotency key
        IdempotencyKey newRecord = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS))
                .build();
        idempotencyKeyRepository.save(newRecord);
        return Optional.empty();
    }

    /**
     * Complete idempotency tracking upon successful ledger entry commit.
     */
    @Transactional
    public void completeIdempotency(String idempotencyKey, Object responseSnapshot) {
        idempotencyKeyRepository.findById(idempotencyKey).ifPresent(record -> {
            try {
                record.setStatus(IdempotencyStatus.COMPLETED);
                record.setResponseSnapshot(objectMapper.writeValueAsString(responseSnapshot));
                record.setUpdatedAt(Instant.now());
                idempotencyKeyRepository.save(record);
            } catch (Exception e) {
                log.error("Failed to serialize response snapshot for key: {}", idempotencyKey, e);
            }
        });
    }

    /**
     * Mark idempotency as FAILED in an isolated transaction so the failure result is permanently cached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failIdempotency(String idempotencyKey, Object errorSnapshot) {
        idempotencyKeyRepository.findById(idempotencyKey).ifPresent(record -> {
            try {
                record.setStatus(IdempotencyStatus.FAILED);
                record.setResponseSnapshot(objectMapper.writeValueAsString(errorSnapshot));
                record.setUpdatedAt(Instant.now());
                idempotencyKeyRepository.save(record);
            } catch (Exception e) {
                log.error("Failed to serialize error snapshot for key: {}", idempotencyKey, e);
            }
        });
    }

    public String computeHash(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Error computing request hash", e);
        }
    }
}
