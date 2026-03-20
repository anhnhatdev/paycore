package com.paycore.ledgerservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {
    private UUID accountId;
    private String currency;
    private BigDecimal availableBalance;
    private BigDecimal pendingBalance;
    private Long version;
    private Instant updatedAt;
}
