package com.paycore.ledgerservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class CreateLedgerEntryRequest {

    @NotNull(message = "transactionId is required")
    private UUID transactionId;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    @NotNull(message = "debitAccountId is required")
    private UUID debitAccountId;

    @NotNull(message = "creditAccountId is required")
    private UUID creditAccountId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be 3-character ISO code")
    private String currency;
}
