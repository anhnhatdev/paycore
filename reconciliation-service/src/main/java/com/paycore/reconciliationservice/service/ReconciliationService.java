package com.paycore.reconciliationservice.service;

import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;

import java.time.Instant;
import java.util.UUID;

public interface ReconciliationService {

    /**
     * Executes a reconciliation job of the specified type for a given period.
     */
    ReconciliationRun executeReconciliation(ReconciliationRunType runType, Instant periodStart, Instant periodEnd);

    /**
     * Resolves an open discrepancy with human audit trail notes.
     */
    void resolveDiscrepancy(UUID discrepancyId, String resolvedBy, String resolutionNote, boolean isFalsePositive);
}
