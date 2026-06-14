package com.paycore.notificationservice.domain.enums;

import java.util.Set;

public enum NotificationEventType {
    TransactionCompleted,
    TransactionFailed,
    TransactionCompensated,
    AccountFrozen,
    GatewayPaymentSuccess,
    GatewayPaymentFailed,
    GatewayPaymentExpired,
    FraudReviewApproved,
    FraudReviewRejected;

    // Security-critical events that CANNOT be disabled by users
    private static final Set<NotificationEventType> NON_OPTIONAL_EVENTS = Set.of(
            AccountFrozen,
            TransactionCompensated,
            FraudReviewApproved,
            FraudReviewRejected
    );

    public boolean isNonOptional() {
        return NON_OPTIONAL_EVENTS.contains(this);
    }

    public static boolean isNonOptional(String eventTypeName) {
        try {
            return NotificationEventType.valueOf(eventTypeName).isNonOptional();
        } catch (IllegalArgumentException e) {
            // Unrecognized custom event defaults to optional
            return false;
        }
    }
}
