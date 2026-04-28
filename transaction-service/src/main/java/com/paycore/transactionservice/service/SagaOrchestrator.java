package com.paycore.transactionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.transactionservice.client.FraudServiceClient;
import com.paycore.transactionservice.client.LedgerServiceClient;
import com.paycore.transactionservice.domain.entity.OutboxEvent;
import com.paycore.transactionservice.domain.entity.Transaction;
import com.paycore.transactionservice.domain.enums.SagaStepName;
import com.paycore.transactionservice.domain.enums.SagaStepStatus;
import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.dto.TransactionResponse;
import com.paycore.transactionservice.dto.client.*;
import com.paycore.transactionservice.exception.FraudRejectedException;
import com.paycore.transactionservice.exception.FraudServiceUnavailableException;
import com.paycore.transactionservice.exception.InsufficientBalanceException;
import com.paycore.transactionservice.exception.LedgerServiceUnavailableException;
import com.paycore.transactionservice.idempotency.IdempotencyManager;
import com.paycore.transactionservice.repository.OutboxEventRepository;
import com.paycore.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {

    private final FraudServiceClient fraudServiceClient;
    private final LedgerServiceClient ledgerServiceClient;
    private final SagaLogService sagaLogService;
    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyManager idempotencyManager;
    private final ObjectMapper objectMapper;

    @Value("${transaction.saga.max-ledger-retries:3}")
    private int maxLedgerRetries;

    /**
     * Executes the Saga for a transaction (Transfer / Deposit / Withdraw).
     */
    public Transaction executeSaga(Transaction transaction) {
        UUID txId = transaction.getId();
        log.info("Starting Saga execution for transaction: id={}, type={}, amount={} {}",
                txId, transaction.getType(), transaction.getAmount(), transaction.getCurrency());

        // Step 0: Initialize
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setUpdatedAt(java.time.Instant.now());
        transactionRepository.saveAndFlush(transaction);
        sagaLogService.recordStep(txId, SagaStepName.INIT, SagaStepStatus.SUCCESS, null, Map.of("status", "PROCESSING"), null);

        // Step 1: Fraud Check (Fail-closed)
        performFraudCheck(transaction);

        // Step 2: Ledger Double-Entry
        performLedgerDebitCredit(transaction);

        // Step 3: Complete Transaction
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setUpdatedAt(java.time.Instant.now());
        transactionRepository.saveAndFlush(transaction);

        // Record outbox event
        recordOutboxEvent(txId, "TransactionCompleted", Map.of(
                "transactionId", txId,
                "userId", transaction.getUserId(),
                "amount", transaction.getAmount(),
                "currency", transaction.getCurrency(),
                "status", "COMPLETED",
                "type", transaction.getType().name()
        ));

        // Complete client idempotency snapshot
        TransactionResponse snapshot = toResponse(transaction);
        idempotencyManager.completeIdempotency(transaction.getClientIdempotencyKey(), txId, snapshot);

        sagaLogService.recordStep(txId, SagaStepName.NOTIFY, SagaStepStatus.SUCCESS, null, Map.of("event", "TransactionCompleted"), null);
        log.info("Saga execution completed successfully for transaction: id={}", txId);

        return transaction;
    }

    /**
     * Step 1: Fraud evaluation with fail-closed security policy.
     */
    private void performFraudCheck(Transaction transaction) {
        UUID txId = transaction.getId();
        sagaLogService.recordStep(txId, SagaStepName.FRAUD_CHECK, SagaStepStatus.STARTED, null, null, null);

        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(txId)
                .userId(transaction.getUserId())
                .fromAccountId(transaction.getFromAccountId())
                .toAccountId(transaction.getToAccountId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .transactionType(transaction.getType().name())
                .build();

        FraudCheckResponse response;
        try {
            response = fraudServiceClient.evaluateRisk(request);
        } catch (Exception e) {
            log.error("Fraud service invocation failed for tx {}: {}", txId, e.getMessage());
            sagaLogService.recordStep(txId, SagaStepName.FRAUD_CHECK, SagaStepStatus.FAILED, request, null, "FRAUD_SERVICE_UNAVAILABLE: " + e.getMessage());
            failTransaction(txId, "FRAUD_SERVICE_UNAVAILABLE");
            throw new FraudServiceUnavailableException("Fraud service is unavailable; transaction rejected for security (fail-closed)");
        }

        if (response == null || !response.isApproved()) {
            String reason = response != null && response.getReason() != null ? response.getReason() : "FRAUD_REJECTED";
            log.warn("Fraud check rejected transaction {}: reason={}", txId, reason);
            sagaLogService.recordStep(txId, SagaStepName.FRAUD_CHECK, SagaStepStatus.FAILED, request, response, reason);
            failTransaction(txId, reason);
            throw new FraudRejectedException("Transaction rejected by fraud detection system: " + reason);
        }

        sagaLogService.recordStep(txId, SagaStepName.FRAUD_CHECK, SagaStepStatus.SUCCESS, request, response, null);
    }

    /**
     * Step 2: Double-entry ledger recording with retry logic.
     */
    private void performLedgerDebitCredit(Transaction transaction) {
        UUID txId = transaction.getId();
        String ledgerIdempotencyKey = IdempotencyManager.getLedgerDebitCreditKey(txId);

        CreateLedgerEntryClientRequest request = CreateLedgerEntryClientRequest.builder()
                .transactionId(txId)
                .idempotencyKey(ledgerIdempotencyKey)
                .debitAccountId(transaction.getFromAccountId())
                .creditAccountId(transaction.getToAccountId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .build();

        sagaLogService.recordStep(txId, SagaStepName.LEDGER_DEBIT_CREDIT, SagaStepStatus.STARTED, request, null, null);

        int attempts = 0;
        ResponseEntity<CreateLedgerEntryClientResponse> responseEntity = null;
        Exception lastException = null;

        while (attempts < maxLedgerRetries) {
            attempts++;
            try {
                responseEntity = ledgerServiceClient.processDoubleEntry(request);
                if (responseEntity != null && responseEntity.getStatusCode().is2xxSuccessful()) {
                    break;
                }
            } catch (feign.FeignException.UnprocessableEntity e) {
                // Business failure (e.g. Insufficient Balance) -> No retry, fail immediately
                log.warn("Ledger rejected transaction {} with HTTP 422: {}", txId, e.contentUTF8());
                sagaLogService.recordStep(txId, SagaStepName.LEDGER_DEBIT_CREDIT, SagaStepStatus.FAILED, request, null, "INSUFFICIENT_BALANCE");
                failTransaction(txId, "INSUFFICIENT_BALANCE");
                throw new InsufficientBalanceException("Insufficient account balance to process transaction");
            } catch (Exception e) {
                lastException = e;
                log.warn("Transient error calling ledger service for tx {} (attempt {}/{}): {}", txId, attempts, maxLedgerRetries, e.getMessage());
                try {
                    Thread.sleep(100L * attempts);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (responseEntity == null || !responseEntity.getStatusCode().is2xxSuccessful() || responseEntity.getBody() == null) {
            String errorMsg = lastException != null ? lastException.getMessage() : "Ledger call failed after retries";
            log.error("Exhausted retries calling ledger service for tx {}: {}", txId, errorMsg);
            sagaLogService.recordStep(txId, SagaStepName.LEDGER_DEBIT_CREDIT, SagaStepStatus.FAILED, request, null, "LEDGER_UNAVAILABLE: " + errorMsg);
            failTransaction(txId, "LEDGER_UNAVAILABLE");
            throw new LedgerServiceUnavailableException("Wallet Ledger service is unavailable after retries");
        }

        CreateLedgerEntryClientResponse ledgerResponse = responseEntity.getBody();
        transaction.setLedgerDebitEntryId(ledgerResponse.getDebitEntryId());
        transaction.setLedgerCreditEntryId(ledgerResponse.getCreditEntryId());

        sagaLogService.recordStep(txId, SagaStepName.LEDGER_DEBIT_CREDIT, SagaStepStatus.SUCCESS, request, ledgerResponse, null);
    }

    /**
     * Step 3 (Compensation): Compensates an executed ledger step via reversal entry.
     */
    @Transactional
    public void compensateTransaction(Transaction transaction, String reason) {
        UUID txId = transaction.getId();
        log.warn("Compensating transaction {}: reason={}", txId, reason);

        transaction.setStatus(TransactionStatus.COMPENSATING);
        transactionRepository.save(transaction);
        sagaLogService.recordStep(txId, SagaStepName.LEDGER_REVERSAL, SagaStepStatus.STARTED, Map.of("reason", reason), null, null);

        String reversalKey = IdempotencyManager.getLedgerReversalKey(txId);
        ReverseLedgerEntryClientRequest revRequest = ReverseLedgerEntryClientRequest.builder()
                .originalTransactionId(txId)
                .idempotencyKey(reversalKey)
                .reason(reason)
                .build();

        try {
            ResponseEntity<ReverseLedgerEntryClientResponse> response = ledgerServiceClient.processReversal(revRequest);
            transaction.setStatus(TransactionStatus.COMPENSATED);
            transaction.setFailureReason(reason);
            transactionRepository.save(transaction);

            recordOutboxEvent(txId, "TransactionCompensated", Map.of(
                    "transactionId", txId,
                    "reason", reason,
                    "status", "COMPENSATED"
            ));

            idempotencyManager.failIdempotency(transaction.getClientIdempotencyKey(), txId, toResponse(transaction));
            sagaLogService.recordStep(txId, SagaStepName.LEDGER_REVERSAL, SagaStepStatus.SUCCESS, revRequest, response != null ? response.getBody() : null, null);
            log.info("Transaction {} successfully compensated", txId);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to compensate transaction {}: {}", txId, e.getMessage(), e);
            sagaLogService.recordStep(txId, SagaStepName.LEDGER_REVERSAL, SagaStepStatus.FAILED, revRequest, null, e.getMessage());
            // Left in COMPENSATING state for Stuck Transaction Reaper alert
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void failTransaction(UUID transactionId, String failureReason) {
        Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction != null) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(failureReason);
            transaction.setUpdatedAt(java.time.Instant.now());
            transactionRepository.saveAndFlush(transaction);

            recordOutboxEvent(transaction.getId(), "TransactionFailed", Map.of(
                    "transactionId", transaction.getId(),
                    "failureReason", failureReason,
                    "status", "FAILED"
            ));

            TransactionResponse response = toResponse(transaction);
            idempotencyManager.failIdempotency(transaction.getClientIdempotencyKey(), transaction.getId(), response);
        }
    }

    private void recordOutboxEvent(UUID aggregateId, String eventType, Object payload) {
        try {
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(payload))
                    .published(false)
                    .build();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to write outbox event for tx {}", aggregateId, e);
        }
    }

    public TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .transactionId(t.getId())
                .clientIdempotencyKey(t.getClientIdempotencyKey())
                .userId(t.getUserId())
                .fromAccountId(t.getFromAccountId())
                .toAccountId(t.getToAccountId())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .type(t.getType())
                .status(t.getStatus())
                .failureReason(t.getFailureReason())
                .ledgerDebitEntryId(t.getLedgerDebitEntryId())
                .ledgerCreditEntryId(t.getLedgerCreditEntryId())
                .description(t.getDescription())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
