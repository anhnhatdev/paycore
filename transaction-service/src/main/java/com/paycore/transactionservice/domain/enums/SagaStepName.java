package com.paycore.transactionservice.domain.enums;

public enum SagaStepName {
    INIT,
    FRAUD_CHECK,
    ACCOUNT_VALIDATION,
    LEDGER_DEBIT_CREDIT,
    PAYMENT_GATEWAY,
    LEDGER_REVERSAL,
    NOTIFY
}
