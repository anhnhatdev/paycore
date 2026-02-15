package com.paycore.accountservice.event;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published on successful login.
 * Consumed by: audit-service (immutable audit trail)
 * IMPORTANT: Must never contain password, tokens, or any PII beyond userId/email.
 */
@Data
@Builder
public class UserLoggedInEvent {
    private UUID userId;
    private String email;
    private LocalDateTime loginAt;
}
