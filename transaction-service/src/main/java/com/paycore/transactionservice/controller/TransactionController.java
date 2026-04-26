package com.paycore.transactionservice.controller;

import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.dto.DepositRequest;
import com.paycore.transactionservice.dto.TransactionResponse;
import com.paycore.transactionservice.dto.TransferRequest;
import com.paycore.transactionservice.dto.WithdrawRequest;
import com.paycore.transactionservice.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transaction Management", description = "Endpoints for initiating transfers, deposits, withdrawals, and tracking Saga states")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    @Operation(summary = "Initiate P2P Transfer", description = "Initiates a money transfer from authenticated user's wallet to destination account")
    public ResponseEntity<TransactionResponse> transfer(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000010") String userIdHeader,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        UUID userId = UUID.fromString(userIdHeader);
        TransactionResponse response = transactionService.initiateTransfer(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/deposit")
    @Operation(summary = "Initiate Deposit", description = "Top-up user wallet from external payment gateway via system suspense account")
    public ResponseEntity<TransactionResponse> deposit(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000010") String userIdHeader,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        UUID userId = UUID.fromString(userIdHeader);
        TransactionResponse response = transactionService.initiateDeposit(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Initiate Withdrawal", description = "Withdraw funds from user wallet to external bank account")
    public ResponseEntity<TransactionResponse> withdraw(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000010") String userIdHeader,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request) {

        UUID userId = UUID.fromString(userIdHeader);
        TransactionResponse response = transactionService.initiateWithdraw(userId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Transaction Details", description = "Retrieve complete transaction record including Saga execution logs")
    public ResponseEntity<TransactionResponse> getTransactionById(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000010") String userIdHeader,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Role", defaultValue = "ROLE_USER") String role,
            @PathVariable("id") UUID transactionId) {

        UUID userId = UUID.fromString(userIdHeader);
        TransactionResponse response = transactionService.getTransactionById(userId, role, transactionId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "Get Transaction History", description = "Paginated list of transactions for current user")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000010") String userIdHeader,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Role", defaultValue = "ROLE_USER") String role,
            @RequestParam(value = "status", required = false) TransactionStatus status,
            @PageableDefault(size = 20) Pageable pageable) {

        UUID userId = UUID.fromString(userIdHeader);
        Page<TransactionResponse> page = transactionService.getTransactions(userId, role, status, pageable);
        return ResponseEntity.ok(page);
    }
}
