package com.paycore.reconciliationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalLedgerTotalsDto {
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private BigDecimal difference;
    private boolean balanced;
}
