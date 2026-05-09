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
public class WebhookResult {
    private String providerEventId;
    private String providerTransactionRef;
    private String internalTransactionRef;
    private BigDecimal amount;
    private String currency;
    private GatewayTransactionStatus status;
    private String responseCode;
    private String message;
    private String rawPayload;
}
