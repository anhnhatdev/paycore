package com.paycore.ledgerservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLedgerEntryResponse {
    private UUID debitEntryId;
    private UUID creditEntryId;
    private BigDecimal debitBalanceAfter;
    private BigDecimal creditBalanceAfter;
    @Builder.Default
    private String status = "COMPLETED";
}
