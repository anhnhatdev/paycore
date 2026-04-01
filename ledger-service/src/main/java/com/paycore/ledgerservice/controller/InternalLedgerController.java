package com.paycore.ledgerservice.controller;

import com.paycore.ledgerservice.domain.entity.LedgerEntry;
import com.paycore.ledgerservice.dto.*;
import com.paycore.ledgerservice.service.LedgerService;
import com.paycore.ledgerservice.service.ReconciliationService;
import com.paycore.ledgerservice.service.ReversalService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal REST Controller for Ledger Operations.
 * Strictly internal service-to-service communication; blocked from external API Gateway access.
 */
@RestController
@RequestMapping("/internal/v1/ledger")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal Ledger API", description = "mTLS-only endpoints for double-entry bookkeeping, balances, and reconciliation")
public class InternalLedgerController {

    private final LedgerService ledgerService;
    private final ReversalService reversalService;
    private final ReconciliationService reconciliationService;

    @PostMapping("/entries")
    @Operation(summary = "Process double-entry transaction (DEBIT + CREDIT)")
    public ResponseEntity<CreateLedgerEntryResponse> createDoubleEntry(
            @Valid @RequestBody CreateLedgerEntryRequest request) {
        log.info("REST_REQUEST | POST /internal/v1/ledger/entries | txId={} | idempotencyKey={}",
                request.getTransactionId(), request.getIdempotencyKey());
        CreateLedgerEntryResponse response = ledgerService.processDoubleEntry(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/entries/reversal")
    @Operation(summary = "Create compensating reversal double-entry for an existing transaction")
    public ResponseEntity<ReverseLedgerEntryResponse> reverseEntry(
            @Valid @RequestBody ReverseLedgerEntryRequest request) {
        log.info("REST_REQUEST | POST /internal/v1/ledger/entries/reversal | origTxId={}",
                request.getOriginalTransactionId());
        ReverseLedgerEntryResponse response = reversalService.processReversal(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/balance/{accountId}")
    @Operation(summary = "Get current available and pending balance snapshot for an account")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable UUID accountId) {
        log.debug("REST_REQUEST | GET /internal/v1/ledger/balance/{}", accountId);
        BalanceResponse response = ledgerService.getBalance(accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reconcile/{accountId}")
    @Operation(summary = "Reconcile account balance against complete ledger journal entries")
    public ResponseEntity<ReconciliationResponse> reconcileAccount(@PathVariable UUID accountId) {
        log.info("REST_REQUEST | GET /internal/v1/ledger/reconcile/{}", accountId);
        ReconciliationResponse response = reconciliationService.reconcileAccount(accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/entries")
    @Operation(summary = "Query paginated statement of ledger entries for an account")
    public ResponseEntity<Page<LedgerEntry>> getEntries(
            @RequestParam UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        log.debug("REST_REQUEST | GET /internal/v1/ledger/entries | accountId={}", accountId);
        Page<LedgerEntry> page = ledgerService.getEntries(accountId, from, to, pageable);
        return ResponseEntity.ok(page);
    }
}
