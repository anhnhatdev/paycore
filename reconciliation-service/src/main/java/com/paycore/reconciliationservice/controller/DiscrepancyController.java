package com.paycore.reconciliationservice.controller;

import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.dto.ResolveDiscrepancyRequest;
import com.paycore.reconciliationservice.repository.DiscrepancyRepository;
import com.paycore.reconciliationservice.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/v1/reconciliation/discrepancies")
@RequiredArgsConstructor
@Tag(name = "Discrepancy Management", description = "Endpoints for inspecting and resolving detected discrepancies (Audit & Human Review Only — NEVER alters balances)")
public class DiscrepancyController {

    private final DiscrepancyRepository discrepancyRepository;
    private final ReconciliationService reconciliationService;

    @GetMapping
    @Operation(summary = "List discrepancies with optional filters", description = "Filter by status, severity, or runId")
    public ResponseEntity<?> getDiscrepancies(
            @RequestParam(value = "status", required = false) DiscrepancyStatus status,
            @RequestParam(value = "severity", required = false) DiscrepancySeverity severity,
            @RequestParam(value = "runId", required = false) UUID runId,
            Pageable pageable
    ) {
        if (runId != null) {
            return ResponseEntity.ok(discrepancyRepository.findByReconciliationRunId(runId));
        }
        if (status != null && severity != null) {
            return ResponseEntity.ok(discrepancyRepository.findByStatusAndSeverity(status, severity));
        }
        if (status != null) {
            return ResponseEntity.ok(discrepancyRepository.findByStatus(status));
        }
        return ResponseEntity.ok(discrepancyRepository.findAllByOrderByCreatedAtDesc(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get discrepancy details by ID")
    public ResponseEntity<Discrepancy> getDiscrepancyById(@PathVariable("id") UUID id) {
        return discrepancyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve discrepancy with human audit trail", description = "Records resolution note and operator ID. NOTE: Does NOT modify balances or ledger entries.")
    public ResponseEntity<Void> resolveDiscrepancy(
            @PathVariable("id") UUID id,
            @Valid @RequestBody ResolveDiscrepancyRequest request,
            @RequestHeader(value = "X-Admin-Id", required = false, defaultValue = "ADMIN_USER") String adminId
    ) {
        String resolver = request.getResolvedBy() != null ? request.getResolvedBy() : adminId;
        log.info("REST resolve discrepancy requested: id={}, resolvedBy={}, isFalsePositive={}",
                id, resolver, request.isFalsePositive());

        reconciliationService.resolveDiscrepancy(id, resolver, request.getResolutionNote(), request.isFalsePositive());
        return ResponseEntity.ok().build();
    }
}
