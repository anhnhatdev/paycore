package com.paycore.fraudservice.dto;

import com.paycore.fraudservice.domain.enums.KycStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class FraudCheckRequest {

    @NotNull(message = "transactionId is required")
    private UUID transactionId;

    private UUID fromAccountId;
    private UUID toAccountId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    private String currency;

    private KycStatus kycStatus;
    private String deviceFingerprint;
    private String ipAddress;
}
