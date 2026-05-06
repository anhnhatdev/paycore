package com.paycore.paymentgatewayservice.domain.enums;

public enum WebhookProcessingStatus {
    RECEIVED,
    PROCESSED,
    IGNORED_DUPLICATE,
    REJECTED_INVALID_SIGNATURE
}
