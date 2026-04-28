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
public class CreateLedgerEntryClientRequest {

    private UUID transactionId;
    private String idempotencyKey;
    private UUID debitAccountId;
    private UUID creditAccountId;
    private BigDecimal amount;
    private String currency;
}
