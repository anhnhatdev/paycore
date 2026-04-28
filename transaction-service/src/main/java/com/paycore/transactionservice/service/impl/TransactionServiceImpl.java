package com.paycore.transactionservice.service.impl;

import com.paycore.transactionservice.client.AccountServiceClient;
import com.paycore.transactionservice.domain.entity.SagaLog;
import com.paycore.transactionservice.domain.entity.Transaction;
import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.domain.enums.TransactionType;
import com.paycore.transactionservice.dto.DepositRequest;
import com.paycore.transactionservice.dto.SagaLogResponse;
import com.paycore.transactionservice.dto.TransactionResponse;
import com.paycore.transactionservice.dto.TransferRequest;
import com.paycore.transactionservice.dto.WithdrawRequest;
import com.paycore.transactionservice.dto.client.AccountResolutionResponse;
import com.paycore.transactionservice.exception.*;
import com.paycore.transactionservice.idempotency.IdempotencyManager;
import com.paycore.transactionservice.repository.SagaLogRepository;
import com.paycore.transactionservice.repository.TransactionRepository;
import com.paycore.transactionservice.service.SagaOrchestrator;
import com.paycore.transactionservice.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    // Standard PayCore System Suspense Counterparty UUIDs
    public static final UUID SUSPENSE_ACCOUNT_VND = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID SUSPENSE_ACCOUNT_USD = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final TransactionRepository transactionRepository;
    private final SagaLogRepository sagaLogRepository;
    private final AccountServiceClient accountServiceClient;
    private final SagaOrchestrator sagaOrchestrator;
    private final IdempotencyManager idempotencyManager;

    @Override
    public TransactionResponse initiateTransfer(UUID userId, String idempotencyKey, TransferRequest request) {
        log.info("Initiating P2P transfer: userId={}, toAccount={}, amount={} {}",
                userId, request.getToAccountNumber(), request.getAmount(), request.getCurrency());

        // 1. Client Idempotency Check (Phase 0)
        TransactionResponse cached = idempotencyManager.startOrCheckIdempotency(idempotencyKey, request, TransactionResponse.class);
        if (cached != null) {
            return cached;
        }

        // 2. Resolve sender account
        AccountResolutionResponse senderAccount = resolveSenderAccount(userId, request.getCurrency());

        // 3. Resolve recipient account
        AccountResolutionResponse recipientAccount;
        try {
            recipientAccount = accountServiceClient.getAccountByNumber(request.getToAccountNumber());
        } catch (Exception e) {
            log.error("Failed to resolve recipient account number {}", request.getToAccountNumber(), e);
            throw new AccountNotFoundException("Recipient account number " + request.getToAccountNumber() + " does not exist");
        }

        if (recipientAccount == null) {
            throw new AccountNotFoundException("Recipient account number " + request.getToAccountNumber() + " does not exist");
        }
        if (!"ACTIVE".equalsIgnoreCase(recipientAccount.getStatus())) {
            throw new AccountFrozenException("Recipient account is not active (status: " + recipientAccount.getStatus() + ")");
        }
        if (!request.getCurrency().equalsIgnoreCase(recipientAccount.getCurrency())) {
            throw new IllegalArgumentException("Currency mismatch: recipient account is in " + recipientAccount.getCurrency());
        }
        if (senderAccount.getAccountId().equals(recipientAccount.getAccountId())) {
            throw new IllegalArgumentException("Cannot transfer funds to the same account");
        }

        // 4. Create Transaction record in PENDING status
        Transaction transaction = Transaction.builder()
                .clientIdempotencyKey(idempotencyKey)
                .userId(userId)
                .fromAccountId(senderAccount.getAccountId())
                .toAccountId(recipientAccount.getAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .description(request.getNote())
                .build();

        transaction = transactionRepository.saveAndFlush(transaction);

        // 5. Execute Saga
        Transaction completed = sagaOrchestrator.executeSaga(transaction);
        return mapToResponseWithLogs(completed);
    }

    @Override
    public TransactionResponse initiateDeposit(UUID userId, String idempotencyKey, DepositRequest request) {
        log.info("Initiating Deposit: userId={}, amount={} {}", userId, request.getAmount(), request.getCurrency());

        TransactionResponse cached = idempotencyManager.startOrCheckIdempotency(idempotencyKey, request, TransactionResponse.class);
        if (cached != null) {
            return cached;
        }

        AccountResolutionResponse userAccount = resolveSenderAccount(userId, request.getCurrency());
        UUID suspenseAccountId = "USD".equalsIgnoreCase(request.getCurrency()) ? SUSPENSE_ACCOUNT_USD : SUSPENSE_ACCOUNT_VND;

        Transaction transaction = Transaction.builder()
                .clientIdempotencyKey(idempotencyKey)
                .userId(userId)
                .fromAccountId(suspenseAccountId)
                .toAccountId(userAccount.getAccountId())
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.PENDING)
                .description(request.getNote() != null ? request.getNote() : "Top-up via external gateway")
                .build();

        transaction = transactionRepository.saveAndFlush(transaction);
        Transaction completed = sagaOrchestrator.executeSaga(transaction);
        return mapToResponseWithLogs(completed);
    }

    @Override
    public TransactionResponse initiateWithdraw(UUID userId, String idempotencyKey, WithdrawRequest request) {
        log.info("Initiating Withdrawal: userId={}, amount={} {}", userId, request.getAmount(), request.getCurrency());

        TransactionResponse cached = idempotencyManager.startOrCheckIdempotency(idempotencyKey, request, TransactionResponse.class);
        if (cached != null) {
            return cached;
        }

        AccountResolutionResponse userAccount = resolveSenderAccount(userId, request.getCurrency());
        UUID suspenseAccountId = "USD".equalsIgnoreCase(request.getCurrency()) ? SUSPENSE_ACCOUNT_USD : SUSPENSE_ACCOUNT_VND;

        Transaction transaction = Transaction.builder()
                .clientIdempotencyKey(idempotencyKey)
                .userId(userId)
                .fromAccountId(userAccount.getAccountId())
                .toAccountId(suspenseAccountId)
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .type(TransactionType.WITHDRAW)
                .status(TransactionStatus.PENDING)
                .description(request.getNote() != null ? request.getNote() : "Withdrawal to " + request.getBankCode() + " " + request.getBankAccountNumber())
                .build();

        transaction = transactionRepository.saveAndFlush(transaction);
        Transaction completed = sagaOrchestrator.executeSaga(transaction);
        return mapToResponseWithLogs(completed);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(UUID userId, String role, UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found with id: " + transactionId));

        boolean isAdmin = "ROLE_ADMIN".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
        if (!isAdmin && !transaction.getUserId().equals(userId)) {
            log.warn("Access denied for user {} to transaction {}", userId, transactionId);
            throw new UnauthorizedTransactionAccessException("You are not authorized to view transaction: " + transactionId);
        }

        return mapToResponseWithLogs(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID userId, String role, TransactionStatus status, Pageable pageable) {
        boolean isAdmin = "ROLE_ADMIN".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);

        Page<Transaction> page;
        if (isAdmin && userId == null) {
            page = status != null ? transactionRepository.findAll(pageable) : transactionRepository.findAll(pageable);
        } else {
            page = status != null
                    ? transactionRepository.findByUserIdAndStatus(userId, status, pageable)
                    : transactionRepository.findByUserId(userId, pageable);
        }

        return page.map(sagaOrchestrator::toResponse);
    }

    private AccountResolutionResponse resolveSenderAccount(UUID userId, String currency) {
        AccountResolutionResponse account;
        try {
            account = accountServiceClient.getDefaultAccountByUserId(userId);
        } catch (Exception e) {
            log.error("Failed to retrieve default account for user {}", userId, e);
            throw new AccountNotFoundException("No default account found for user: " + userId);
        }

        if (account == null) {
            throw new AccountNotFoundException("No default account found for user: " + userId);
        }
        if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
            throw new AccountFrozenException("Source account is not active (status: " + account.getStatus() + ")");
        }
        if (!currency.equalsIgnoreCase(account.getCurrency())) {
            throw new IllegalArgumentException("Source account currency (" + account.getCurrency() + ") does not match requested currency (" + currency + ")");
        }

        return account;
    }

    private TransactionResponse mapToResponseWithLogs(Transaction transaction) {
        TransactionResponse response = sagaOrchestrator.toResponse(transaction);
        List<SagaLog> logs = sagaLogRepository.findByTransactionIdOrderByCreatedAtAsc(transaction.getId());
        response.setSagaLogs(logs.stream()
                .map(l -> SagaLogResponse.builder()
                        .id(l.getId())
                        .stepName(l.getStepName())
                        .status(l.getStatus())
                        .requestPayload(l.getRequestPayload())
                        .responsePayload(l.getResponsePayload())
                        .errorMessage(l.getErrorMessage())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList());
        return response;
    }
}
