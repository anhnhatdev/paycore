package com.paycore.paymentgatewayservice.adapter.dto;

import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderQueryStatusResult {
    private String providerTransactionRef;
    private GatewayTransactionStatus status;
    private BigDecimal amount;
    private String currency;
    private String message;
}
