package com.paycore.ledgerservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedLedgerEntryResponse {
    @Builder.Default
    private String status = "FAILED";
    private String reason;
    private BigDecimal availableBalance;
}
