package com.paycore.reconciliationservice.controller;

import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.dto.TriggerReconciliationRequest;
import com.paycore.reconciliationservice.repository.ReconciliationRunRepository;
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

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/v1/reconciliation/runs")
@RequiredArgsConstructor
@Tag(name = "Reconciliation Runs", description = "Endpoints for triggering on-demand runs and inspecting run history")
public class ReconciliationRunController {

    private final ReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;

    @GetMapping
    @Operation(summary = "Get reconciliation runs", description = "List all runs paginated, ordered by startedAt desc")
    public ResponseEntity<Page<ReconciliationRun>> getRuns(Pageable pageable) {
        return ResponseEntity.ok(runRepository.findAllByOrderByStartedAtDesc(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get reconciliation run by ID")
    public ResponseEntity<ReconciliationRun> getRunById(@PathVariable("id") UUID id) {
        return runRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/trigger")
    @Operation(summary = "Trigger reconciliation run on-demand", description = "Executes an immediate run for a specified period (ADMIN only)")
    public ResponseEntity<ReconciliationRun> triggerRun(@Valid @RequestBody TriggerReconciliationRequest request) {
        log.info("REST trigger requested: type={}, period=[{} - {}]",
                request.getRunType(), request.getPeriodStart(), request.getPeriodEnd());
        ReconciliationRun run = reconciliationService.executeReconciliation(
                request.getRunType(), request.getPeriodStart(), request.getPeriodEnd()
        );
        return ResponseEntity.ok(run);
    }
}
