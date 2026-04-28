package com.paycore.transactionservice.dto.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReverseLedgerEntryClientRequest {

    private UUID originalTransactionId;
    private String idempotencyKey;
    private String reason;
}
