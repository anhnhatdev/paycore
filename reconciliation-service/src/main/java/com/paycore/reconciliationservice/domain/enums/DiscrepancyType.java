package com.paycore.reconciliationservice.domain.enums;

public enum DiscrepancyType {
    BALANCE_MISMATCH,
    GLOBAL_INVARIANT_VIOLATION,
    ORPHAN_LEDGER_ENTRY,
    MISSING_LEDGER_ENTRY,
    GATEWAY_AMOUNT_MISMATCH,
    GATEWAY_MISSING_INTERNAL_RECORD
}
