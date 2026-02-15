package com.paycore.accountservice.event;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Published when an account is frozen by an admin.
 * Consumed by: transaction-service (blocks new transactions on this account)
 */
@Data
@Builder
public class AccountFrozenEvent {
    private UUID accountId;
    private String accountNumber;
    private UUID userId;
}
