package com.paycore.auditservice;

import com.paycore.auditservice.controller.AuditController;
import com.paycore.auditservice.domain.entity.AuditAccessLog;
import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.domain.enums.ActorType;
import com.paycore.auditservice.dto.AuditEventEnvelope;
import com.paycore.auditservice.dto.ChainVerificationResult;
import com.paycore.auditservice.hasher.AuditHasher;
import com.paycore.auditservice.repository.AuditAccessLogRepository;
import com.paycore.auditservice.repository.AuditRecordRepository;
import com.paycore.auditservice.repository.ProcessedEventRepository;
import com.paycore.auditservice.service.AuditArchivalService;
import com.paycore.auditservice.service.AuditRecordService;
import com.paycore.auditservice.service.ChainVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class AuditServiceIntegrationTest {

    @Autowired
    private AuditRecordService auditRecordService;

    @Autowired
    private ChainVerificationService chainVerificationService;

    @Autowired
    private AuditArchivalService auditArchivalService;

    @Autowired
    private AuditController auditController;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private AuditAccessLogRepository accessLogRepository;

    @BeforeEach
    void setUp() {
        auditRecordRepository.deleteAll();
        processedEventRepository.deleteAll();
        accessLogRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        auditRecordRepository.deleteAll();
        processedEventRepository.deleteAll();
        accessLogRepository.deleteAll();
    }

    // ─── Test 1: Event Idempotency (Duplicate Delivery) ───────────────────────

    @Test
    @DisplayName("TEST-1: Duplicate event publishing results in exactly ONE audit record (idempotency)")
    void recordAuditEvent_DuplicateEvents_OnlyOneRecorded() {
        UUID eventId = UUID.randomUUID();
        AuditEventEnvelope envelope = buildEnvelope(eventId, "TransactionCompleted", "{\"amount\":\"100.00\"}");

        Optional<AuditRecord> first = auditRecordService.recordAuditEvent(envelope);
        Optional<AuditRecord> second = auditRecordService.recordAuditEvent(envelope);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(auditRecordRepository.findAll()).hasSize(1);
    }

    // ─── Test 2: Sequential Hash Chaining ─────────────────────────────────────

    @Test
    @DisplayName("TEST-2: 5 sequential records maintain unbroken SHA-256 hash chaining (prev_hash == previous record_hash)")
    void recordAuditEvent_SequentialRecords_MaintainsUnbrokenChain() {
        for (int i = 1; i <= 5; i++) {
            AuditEventEnvelope env = buildEnvelope(UUID.randomUUID(), "Event_" + i, "{\"step\":" + i + "}");
            auditRecordService.recordAuditEvent(env);
        }

        List<AuditRecord> records = auditRecordRepository.findBySequenceNumberBetweenOrderBySequenceNumberAsc(1L, 5L);
        assertThat(records).hasSize(5);

        // First record links to GENESIS_HASH
        assertThat(records.get(0).getPrevHash()).isEqualTo(AuditHasher.GENESIS_HASH);

        // Records 1..4 link to previous record's recordHash
        for (int i = 1; i < 5; i++) {
            assertThat(records.get(i).getPrevHash()).isEqualTo(records.get(i - 1).getRecordHash());
        }

        ChainVerificationResult verification = chainVerificationService.verifyChain(1L, 5L);
        assertThat(verification.isValid()).isTrue();
        assertThat(verification.getVerifiedRecordsCount()).isEqualTo(5L);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ─── Test 3: Tamper-Evidence Detection ───────────────────────────────────

    @Test
    @DisplayName("TEST-3: Simulated database tampering is detected immediately with exact sequence number identified")
    void verifyChain_TamperedPayload_DetectedWithExactSequence() {
        for (int i = 1; i <= 3; i++) {
            auditRecordService.recordAuditEvent(buildEnvelope(UUID.randomUUID(), "Event_" + i, "{\"val\":" + i + "}"));
        }

        // Simulate database attack: direct SQL update by malicious DB admin on sequence #2
        jdbcTemplate.update("UPDATE audit_records SET payload = ? WHERE sequence_number = ?",
                "{\"val\":999999,\"hacked\":true}", 2L);

        // Run chain verification
        ChainVerificationResult result = chainVerificationService.verifyChain(1L, 3L);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getCorruptedSequenceNumber()).isEqualTo(2L);
        assertThat(result.getMessage()).contains("Tamper detected");
    }

    // ─── Test 4: Sensitive Payload Redaction ─────────────────────────────────

    @Test
    @DisplayName("TEST-4: Sensitive card numbers, CVVs, and passwords in payload are redacted into [REDACTED]")
    void recordAuditEvent_SensitiveFields_RedactedBeforeHashing() {
        String sensitiveJson = """
                {"cardNumber":"4111222233334444","cvv":"999","password":"secretPassword123","amount":"250000.00"}
                """;

        AuditEventEnvelope envelope = buildEnvelope(UUID.randomUUID(), "CardPaymentReceived", sensitiveJson);
        AuditRecord recorded = auditRecordService.recordAuditEvent(envelope).orElseThrow();

        assertThat(recorded.getPayload()).contains("\"cardNumber\":\"[REDACTED]\"");
        assertThat(recorded.getPayload()).contains("\"cvv\":\"[REDACTED]\"");
        assertThat(recorded.getPayload()).contains("\"password\":\"[REDACTED]\"");
        assertThat(recorded.getPayload()).contains("\"amount\":\"250000.00\"");
        assertThat(recorded.getPayload()).doesNotContain("4111222233334444");
        assertThat(recorded.getPayload()).doesNotContain("secretPassword123");
    }

    // ─── Test 5: Mandatory Meta-Audit Access Logging ──────────────────────────

    @Test
    @DisplayName("TEST-5: Querying audit records automatically generates an audit_access_logs entry")
    void getAuditRecords_AuthorizedQuery_CreatesMetaAuditLog() {
        auditRecordService.recordAuditEvent(buildEnvelope(UUID.randomUUID(), "TestEvent", "{}"));

        ResponseEntity<List<AuditRecord>> response = auditController.getAuditRecords(
                null, null, null, null, null, null, "COMPLIANCE", "compliance_officer_01"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        List<AuditAccessLog> accessLogs = accessLogRepository.findAll();
        assertThat(accessLogs).hasSize(1);
        assertThat(accessLogs.get(0).getAccessedBy()).isEqualTo("compliance_officer_01");
        assertThat(accessLogs.get(0).getResultCount()).isGreaterThanOrEqualTo(1);
    }

    // ─── Test 6: RBAC Protection (Forbidden for non-Admin/Compliance) ─────────

    @Test
    @DisplayName("TEST-6: Unauthorized role (e.g. USER or GUEST) querying audit records returns 403 Forbidden")
    void getAuditRecords_UnauthorizedRole_ThrowsSecurityException() {
        assertThrows(SecurityException.class, () -> {
            auditController.getAuditRecords(
                    null, null, null, null, null, null, "USER", "user_123"
            );
        });
    }

    // ─── Test 7: Archival Job Leaves Audit Trail ──────────────────────────────

    @Test
    @DisplayName("TEST-7: Archival job creates AuditPartitionArchived event in hash chain")
    void archiveOldPartitions_ExecutesArchival_RecordsAuditEvent() {
        // Seed an old record
        AuditRecord oldRecord = auditRecordRepository.save(AuditRecord.builder()
                .sequenceNumber(1L)
                .eventId(UUID.randomUUID())
                .sourceService("test")
                .eventType("OldEvent")
                .actorType(ActorType.SYSTEM)
                .payload("{}")
                .recordHash("hash1")
                .prevHash(AuditHasher.GENESIS_HASH)
                .occurredAt(Instant.now().minusSeconds(86400 * 400)) // 400 days old
                .recordedAt(Instant.now().minusSeconds(86400 * 400))
                .build());

        int archived = auditArchivalService.archiveOldPartitions();

        assertThat(archived).isGreaterThanOrEqualTo(1);

        // Verify that an AuditPartitionArchived record was recorded in the chain
        List<AuditRecord> records = auditRecordRepository.findAll();
        assertThat(records).anyMatch(r -> "AuditPartitionArchived".equals(r.getEventType()));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private AuditEventEnvelope buildEnvelope(UUID eventId, String eventType, String rawPayload) {
        return AuditEventEnvelope.builder()
                .eventId(eventId)
                .sourceService("test-service")
                .eventType(eventType)
                .actorType(ActorType.USER)
                .actorId(UUID.randomUUID().toString())
                .entityType("TRANSACTION")
                .entityId(UUID.randomUUID().toString())
                .rawPayloadJson(rawPayload)
                .occurredAt(Instant.now())
                .build();
    }
}
