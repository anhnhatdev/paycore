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
public class LedgerEntryDto {
    private UUID id;
    private UUID transactionId;
    private UUID accountId;
    private String entryType;
    private BigDecimal amount;
    private Instant createdAt;
}
