package com.paycore.auditservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.auditservice.domain.entity.AuditAccessLog;
import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.domain.entity.HashCheckpoint;
import com.paycore.auditservice.dto.ChainVerificationResult;
import com.paycore.auditservice.repository.AuditAccessLogRepository;
import com.paycore.auditservice.repository.AuditRecordRepository;
import com.paycore.auditservice.service.AuditCheckpointService;
import com.paycore.auditservice.service.ChainVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/internal/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Audit & Compliance", description = "Endpoints for immutable audit logs, hash chain verification, and meta-audit logs")
public class AuditController {

    private final AuditRecordRepository auditRecordRepository;
    private final AuditAccessLogRepository accessLogRepository;
    private final ChainVerificationService chainVerificationService;
    private final AuditCheckpointService checkpointService;
    private final ObjectMapper objectMapper;

    @GetMapping("/records")
    @Operation(summary = "Query audit records (Admin/Compliance Only)", description = "Searches immutable audit logs. Automatically records access in audit_access_logs.")
    public ResponseEntity<List<AuditRecord>> getAuditRecords(
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "entityId", required = false) String entityId,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestHeader(value = "X-User-Role", required = false, defaultValue = "GUEST") String userRole,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "ANONYMOUS") String userId
    ) {
        // 1. RBAC Check: Only COMPLIANCE or ADMIN allowed
        if (!"COMPLIANCE".equalsIgnoreCase(userRole) && !"ADMIN".equalsIgnoreCase(userRole)) {
            log.warn("Unauthorized audit log access attempt: userId={}, role={}", userId, userRole);
            throw new SecurityException("Access denied. Audit logs require COMPLIANCE or ADMIN role.");
        }

        // 2. Query data
        List<AuditRecord> records;
        if (entityType != null && entityId != null) {
            records = auditRecordRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId);
        } else {
            records = auditRecordRepository.findAll();
        }

        // 3. MANDATORY META-AUDIT: Record the access in audit_access_logs
        recordMetaAuditAccess(userId, entityType, entityId, actorId, eventType, records.size());

        return ResponseEntity.ok(records);
    }

    @GetMapping("/verify-chain")
    @Operation(summary = "Verify hash chain integrity", description = "Re-computes cryptographic hash chain over sequence range to prove tamper-evidence")
    public ResponseEntity<ChainVerificationResult> verifyChain(
            @RequestParam(value = "fromSeq", required = false, defaultValue = "1") Long fromSeq,
            @RequestParam(value = "toSeq", required = false) Long toSeq
    ) {
        log.info("REST: Hash chain verification requested fromSeq={} to toSeq={}", fromSeq, toSeq);
        ChainVerificationResult result = chainVerificationService.verifyChain(fromSeq, toSeq);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/access-logs")
    @Operation(summary = "Get meta-audit access logs", description = "Audit trail of who accessed audit logs")
    public ResponseEntity<Page<AuditAccessLog>> getAccessLogs(Pageable pageable) {
        return ResponseEntity.ok(accessLogRepository.findAllByOrderByAccessedAtDesc(pageable));
    }

    @PostMapping("/checkpoints")
    @Operation(summary = "Create manual hash checkpoint")
    public ResponseEntity<HashCheckpoint> createCheckpoint(
            @RequestParam(value = "reference", required = false, defaultValue = "MANUAL_API_TRIGGER") String reference
    ) {
        Optional<HashCheckpoint> checkpoint = checkpointService.createCheckpoint(reference);
        return checkpoint.map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    private void recordMetaAuditAccess(
            String accessedBy,
            String entityType,
            String entityId,
            String actorId,
            String eventType,
            int resultCount
    ) {
        try {
            Map<String, Object> params = new HashMap<>();
            if (entityType != null) params.put("entityType", entityType);
            if (entityId != null) params.put("entityId", entityId);
            if (actorId != null) params.put("actorId", actorId);
            if (eventType != null) params.put("eventType", eventType);

            AuditAccessLog logEntry = AuditAccessLog.builder()
                    .accessedBy(accessedBy)
                    .queryParams(objectMapper.writeValueAsString(params))
                    .resultCount(resultCount)
                    .accessedAt(Instant.now())
                    .build();

            accessLogRepository.save(logEntry);
            log.info("Meta-audit logged: user={}, resultCount={}", accessedBy, resultCount);
        } catch (Exception e) {
            log.error("Failed to record meta-audit log: {}", e.getMessage(), e);
        }
    }
}
