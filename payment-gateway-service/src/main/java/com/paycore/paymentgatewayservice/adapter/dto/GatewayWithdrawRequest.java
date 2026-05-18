package com.paycore.paymentgatewayservice.adapter.dto;

import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
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
public class GatewayWithdrawRequest {
    private UUID internalTransactionId;
    private String idempotencyKey;
    private BigDecimal amount;
    private String currency;
    private PaymentProvider provider;
    private String bankCode;
    private String bankAccountNumber;
    private String bankAccountName;
}
