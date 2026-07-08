package com.paycore.reconciliationservice.dto;

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
public class TransactionSummaryDto {
    private UUID id;
    private UUID userId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
}
