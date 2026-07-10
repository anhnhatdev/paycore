package com.paycore.reconciliationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRow {
    private String providerTransactionRef;
    private BigDecimal amount;
    private String currency;
    private String status;
    private LocalDate settlementDate;
}
