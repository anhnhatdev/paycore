package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.GatewayTransactionDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

@FeignClient(
        name = "payment-gateway-service",
        url = "${paycore.clients.payment-gateway-service-url:http://localhost:8084}",
        fallback = PaymentGatewayClientFallback.class
)
public interface PaymentGatewayClient {

    @GetMapping("/internal/v1/gateway/transactions")
    List<GatewayTransactionDto> getGatewayTransactions(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd
    );

    @GetMapping("/internal/v1/gateway/transactions/by-ref/{providerTransactionRef}")
    GatewayTransactionDto getTransactionByProviderRef(@PathVariable("providerTransactionRef") String providerTransactionRef);
}
