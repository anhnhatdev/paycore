package com.paycore.transactionservice.dto.client;

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
public class CreateLedgerEntryClientResponse {

    private UUID debitEntryId;
    private UUID creditEntryId;
    private BigDecimal debitBalanceAfter;
    private BigDecimal creditBalanceAfter;
    private String status;
    private String reason;
    private BigDecimal availableBalance;
}
