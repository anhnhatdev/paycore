package com.paycore.reconciliationservice.service.impl;

import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunStatus;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.repository.DiscrepancyRepository;
import com.paycore.reconciliationservice.repository.ReconciliationRunRepository;
import com.paycore.reconciliationservice.runner.ReconciliationRunner;
import com.paycore.reconciliationservice.service.ReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ReconciliationServiceImpl implements ReconciliationService {

    private final ReconciliationRunRepository runRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final Map<ReconciliationRunType, ReconciliationRunner> runnerMap = new EnumMap<>(ReconciliationRunType.class);

    public ReconciliationServiceImpl(
            ReconciliationRunRepository runRepository,
            DiscrepancyRepository discrepancyRepository,
            List<ReconciliationRunner> runners
    ) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        for (ReconciliationRunner runner : runners) {
            this.runnerMap.put(runner.getSupportedType(), runner);
        }
    }

    @Override
    @Transactional
    public ReconciliationRun executeReconciliation(ReconciliationRunType runType, Instant periodStart, Instant periodEnd) {
        ReconciliationRunner runner = runnerMap.get(runType);
        if (runner == null) {
            throw new IllegalArgumentException("No reconciliation runner available for type: " + runType);
        }

        ReconciliationRun run = ReconciliationRun.builder()
                .runType(runType)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .status(ReconciliationRunStatus.RUNNING)
                .startedAt(Instant.now())
                .totalChecked(0)
                .totalDiscrepancies(0)
                .build();

        run = runRepository.save(run);
        log.info("Initialized ReconciliationRun: id={}, type={}, period=[{} - {}]",
                run.getId(), runType, periodStart, periodEnd);

        try {
            int discrepancies = runner.runReconciliation(run);
            run.setTotalDiscrepancies(discrepancies);
            run.setStatus(ReconciliationRunStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            log.info("Completed ReconciliationRun: id={}, totalChecked={}, discrepancies={}",
                    run.getId(), run.getTotalChecked(), discrepancies);
        } catch (Exception e) {
            log.error("ReconciliationRun {} failed: {}", run.getId(), e.getMessage(), e);
            run.setStatus(ReconciliationRunStatus.FAILED);
            run.setCompletedAt(Instant.now());
        }

        return runRepository.save(run);
    }

    @Override
    @Transactional
    public void resolveDiscrepancy(UUID discrepancyId, String resolvedBy, String resolutionNote, boolean isFalsePositive) {
        Discrepancy discrepancy = discrepancyRepository.findById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException("Discrepancy not found: " + discrepancyId));

        discrepancy.setStatus(isFalsePositive ? DiscrepancyStatus.FALSE_POSITIVE : DiscrepancyStatus.RESOLVED);
        discrepancy.setResolvedBy(resolvedBy != null ? resolvedBy : "ADMIN");
        discrepancy.setResolutionNote(resolutionNote);
        discrepancy.setResolvedAt(Instant.now());

        discrepancyRepository.save(discrepancy);
        log.info("Resolved discrepancy {}: status={}, resolvedBy={}, note={}",
                discrepancyId, discrepancy.getStatus(), resolvedBy, resolutionNote);
    }
}
