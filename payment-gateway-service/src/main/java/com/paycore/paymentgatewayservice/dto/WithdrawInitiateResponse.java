package com.paycore.paymentgatewayservice.dto;

import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawInitiateResponse {
    private UUID gatewayTransactionId;
    private UUID internalTransactionId;
    private PaymentProvider provider;
    private String providerTransactionRef;
    private Instant expiresAt;
    private GatewayTransactionStatus status;
}
