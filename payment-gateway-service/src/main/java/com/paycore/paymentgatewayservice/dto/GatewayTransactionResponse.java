package com.paycore.paymentgatewayservice.dto;

import com.paycore.paymentgatewayservice.domain.enums.GatewayDirection;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayTransactionResponse {
    private UUID id;
    private UUID internalTransactionId;
    private PaymentProvider provider;
    private String providerTransactionRef;
    private GatewayDirection direction;
    private BigDecimal amount;
    private String currency;
    private GatewayTransactionStatus status;
    private String checkoutUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
}
