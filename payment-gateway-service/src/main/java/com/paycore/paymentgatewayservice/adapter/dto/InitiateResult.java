package com.paycore.paymentgatewayservice.adapter.dto;

import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateResult {
    private String providerTransactionRef;
    private String checkoutUrl;
    private Instant expiresAt;
    private GatewayTransactionStatus status;
    private String message;
}
