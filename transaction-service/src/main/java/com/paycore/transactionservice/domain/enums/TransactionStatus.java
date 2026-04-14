package com.paycore.transactionservice.domain.enums;

public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED
}
