package com.paycore.paymentgatewayservice.domain.enums;

public enum GatewayTransactionStatus {
    INITIATED,
    PENDING_PROVIDER,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
