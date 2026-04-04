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
public class ReconciliationResponse {
    private UUID accountId;
    private BigDecimal balanceStored;
    private BigDecimal balanceCalculated;
    private Boolean isBalanced;
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private Instant reconciliationTimestamp;
}
