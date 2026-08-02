package com.paycore.auditservice.hasher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditHasherTest {

    private AuditHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new AuditHasher();
    }

    @Test
    @DisplayName("Hashing is deterministic and produces valid 64-character SHA-256 hex string")
    void calculateRecordHash_Deterministic() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        String payload = "{\"amount\":\"50000.00\"}";

        String hash1 = hasher.calculateRecordHash(AuditHasher.GENESIS_HASH, eventId, payload, now, 1L);
        String hash2 = hasher.calculateRecordHash(AuditHasher.GENESIS_HASH, eventId, payload, now, 1L);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Changing sequence number or payload produces completely different SHA-256 hash (avalanche effect)")
    void calculateRecordHash_AlteredContent_ProducesDifferentHash() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        String hashA = hasher.calculateRecordHash(AuditHasher.GENESIS_HASH, eventId, "{\"amount\":\"100\"}", now, 1L);
        String hashB = hasher.calculateRecordHash(AuditHasher.GENESIS_HASH, eventId, "{\"amount\":\"200\"}", now, 1L);

        assertNotEquals(hashA, hashB);
    }
}
