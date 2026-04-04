package com.paycore.ledgerservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReverseLedgerEntryResponse {
    private UUID originalTransactionId;
    private UUID reversalDebitEntryId;
    private UUID reversalCreditEntryId;
    @Builder.Default
    private String status = "REVERSED";
}
