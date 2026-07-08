package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.AccountReconciliationDto;
import com.paycore.reconciliationservice.dto.GlobalLedgerTotalsDto;
import com.paycore.reconciliationservice.dto.LedgerEntryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "wallet-ledger-service",
        url = "${paycore.clients.ledger-service-url:http://localhost:8082}",
        fallback = LedgerClientFallback.class
)
public interface LedgerClient {

    @GetMapping("/internal/v1/ledger/reconcile/{accountId}")
    AccountReconciliationDto reconcileAccount(@PathVariable("accountId") UUID accountId);

    @GetMapping("/internal/v1/ledger/global-totals")
    GlobalLedgerTotalsDto getGlobalTotals(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd
    );

    @GetMapping("/internal/v1/ledger/entries/by-transaction/{transactionId}")
    List<LedgerEntryDto> getEntriesByTransactionId(@PathVariable("transactionId") UUID transactionId);

    @GetMapping("/internal/v1/ledger/active-accounts")
    List<UUID> getActiveAccountIds(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd
    );

    @GetMapping("/internal/v1/ledger/recent-entry-transaction-ids")
    List<UUID> getRecentTransactionIds(
            @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd
    );
}
