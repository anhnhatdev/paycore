package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.TransactionSummaryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "transaction-service",
        url = "${paycore.clients.transaction-service-url:http://localhost:8083}",
        fallback = TransactionClientFallback.class
)
public interface TransactionClient {

    @GetMapping("/internal/v1/transactions/completed")
    List<TransactionSummaryDto> getCompletedTransactions(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd
    );

    @GetMapping("/internal/v1/transactions/{id}")
    TransactionSummaryDto getTransactionById(@PathVariable("id") UUID id);
}
