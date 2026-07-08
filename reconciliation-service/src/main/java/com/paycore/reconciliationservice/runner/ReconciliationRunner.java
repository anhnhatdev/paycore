package com.paycore.reconciliationservice.runner;

import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;

public interface ReconciliationRunner {

    ReconciliationRunType getSupportedType();

    /**
     * Executes the reconciliation logic for the specified run.
     * @param run the ongoing reconciliation run metadata
     * @return the number of discrepancies identified
     */
    int runReconciliation(ReconciliationRun run);
}
