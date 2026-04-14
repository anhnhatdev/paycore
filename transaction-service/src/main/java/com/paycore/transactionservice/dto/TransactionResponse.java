package com.paycore.transactionservice.dto;

import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.domain.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID transactionId;
    private String clientIdempotencyKey;
    private UUID userId;
    private UUID fromAccountId;
    private UUID toAccountId;
    private BigDecimal amount;
    private String currency;
    private TransactionType type;
    private TransactionStatus status;
    private String failureReason;
    private UUID ledgerDebitEntryId;
    private UUID ledgerCreditEntryId;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
    private List<SagaLogResponse> sagaLogs;
}
