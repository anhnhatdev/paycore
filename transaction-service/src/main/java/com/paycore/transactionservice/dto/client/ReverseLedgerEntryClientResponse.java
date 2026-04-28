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
public class ReverseLedgerEntryClientResponse {

    private UUID reversalDebitEntryId;
    private UUID reversalCreditEntryId;
    private String status;
}
