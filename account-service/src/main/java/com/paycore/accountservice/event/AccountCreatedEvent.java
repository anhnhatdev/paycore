package com.paycore.accountservice.event;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Published when a new account is successfully created.
 * Consumed by: ledger-service (initializes balance record = 0)
 */
@Data
@Builder
public class AccountCreatedEvent {
    private UUID userId;
    private UUID accountId;
    private String accountNumber;
    private String currency;
}
