package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.GatewayTransactionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class PaymentGatewayClientFallback implements PaymentGatewayClient {

    @Override
    public List<GatewayTransactionDto> getGatewayTransactions(Instant periodStart, Instant periodEnd) {
        log.warn("PaymentGatewayClient fallback triggered for getGatewayTransactions");
        return Collections.emptyList();
    }

    @Override
    public GatewayTransactionDto getTransactionByProviderRef(String providerTransactionRef) {
        log.warn("PaymentGatewayClient fallback triggered for getTransactionByProviderRef: {}", providerTransactionRef);
        return null;
    }
}
