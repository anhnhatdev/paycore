package com.paycore.transactionservice;

import com.paycore.transactionservice.client.AccountServiceClient;
import com.paycore.transactionservice.client.FraudServiceClient;
import com.paycore.transactionservice.client.LedgerServiceClient;
import com.paycore.transactionservice.domain.entity.OutboxEvent;
import com.paycore.transactionservice.domain.entity.SagaLog;
import com.paycore.transactionservice.domain.entity.Transaction;
import com.paycore.transactionservice.domain.enums.SagaStepName;
import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.domain.enums.TransactionType;
import com.paycore.transactionservice.dto.DepositRequest;
import com.paycore.transactionservice.dto.TransactionResponse;
import com.paycore.transactionservice.dto.TransferRequest;
import com.paycore.transactionservice.dto.WithdrawRequest;
import com.paycore.transactionservice.dto.client.*;
import com.paycore.transactionservice.exception.*;
import com.paycore.transactionservice.idempotency.IdempotencyManager;
import com.paycore.transactionservice.outbox.OutboxPublisher;
import com.paycore.transactionservice.reaper.StuckTransactionReaper;
import com.paycore.transactionservice.repository.IdempotencyKeyRepository;
import com.paycore.transactionservice.repository.OutboxEventRepository;
import com.paycore.transactionservice.repository.SagaLogRepository;
import com.paycore.transactionservice.repository.TransactionRepository;
import com.paycore.transactionservice.service.SagaOrchestrator;
import com.paycore.transactionservice.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SagaOrchestratorIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private SagaOrchestrator sagaOrchestrator;

    @Autowired
    private StuckTransactionReaper stuckTransactionReaper;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private SagaLogRepository sagaLogRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    @MockitoBean
    private LedgerServiceClient ledgerServiceClient;

    @MockitoBean
    private FraudServiceClient fraudServiceClient;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private UUID userId;
    private UUID userAccountId;
    private UUID recipientAccountId;
    private String recipientAccountNumber;

    @BeforeEach
    void setUp() {
        sagaLogRepository.deleteAll();
        outboxEventRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        transactionRepository.deleteAll();

        userId = UUID.randomUUID();
        userAccountId = UUID.randomUUID();
        recipientAccountId = UUID.randomUUID();
        recipientAccountNumber = "PC000000000123";

        // Default mock behaviors
        when(accountServiceClient.getDefaultAccountByUserId(userId)).thenReturn(
                AccountResolutionResponse.builder()
                        .accountId(userAccountId)
                        .userId(userId)
                        .accountNumber("PC000000000999")
                        .currency("VND")
                        .status("ACTIVE")
                        .build()
        );

        when(accountServiceClient.getAccountByNumber(recipientAccountNumber)).thenReturn(
                AccountResolutionResponse.builder()
                        .accountId(recipientAccountId)
                        .userId(UUID.randomUUID())
                        .accountNumber(recipientAccountNumber)
                        .currency("VND")
                        .status("ACTIVE")
                        .build()
        );

        when(fraudServiceClient.evaluateRisk(any())).thenReturn(
                FraudCheckResponse.builder()
                        .approved(true)
                        .riskLevel("LOW")
                        .build()
        );

        when(ledgerServiceClient.processDoubleEntry(any())).thenReturn(
                ResponseEntity.ok(CreateLedgerEntryClientResponse.builder()
                        .debitEntryId(UUID.randomUUID())
                        .creditEntryId(UUID.randomUUID())
                        .status("COMPLETED")
                        .build())
        );

        when(ledgerServiceClient.processReversal(any())).thenReturn(
                ResponseEntity.ok(ReverseLedgerEntryClientResponse.builder()
                        .reversalDebitEntryId(UUID.randomUUID())
                        .reversalCreditEntryId(UUID.randomUUID())
                        .status("REVERSED")
                        .build())
        );
    }

    @Test
    @DisplayName("P2P Transfer completes full Saga successfully: Fraud pass -> Ledger pass -> Outbox event")
    void initiateTransfer_Success() {
        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("500000.00"))
                .currency("VND")
                .note("Lunch money")
                .build();

        TransactionResponse response = transactionService.initiateTransfer(userId, idempotencyKey, request);

        assertNotNull(response);
        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        assertEquals(new BigDecimal("500000.00"), response.getAmount());
        assertNotNull(response.getLedgerDebitEntryId());
        assertNotNull(response.getLedgerCreditEntryId());

        // Verify saga logs: INIT, FRAUD_CHECK (STARTED, SUCCESS), LEDGER_DEBIT_CREDIT (STARTED, SUCCESS), NOTIFY
        List<SagaLog> logs = sagaLogRepository.findByTransactionIdOrderByCreatedAtAsc(response.getTransactionId());
        assertEquals(6, logs.size());

        // Verify outbox
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals("TransactionCompleted", events.get(0).getEventType());
    }

    @Test
    @DisplayName("Duplicate client Idempotency-Key returns cached response without duplicate execution")
    void initiateTransfer_IdempotentDuplicate_ReturnsCached() {
        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .note("Duplicate test")
                .build();

        TransactionResponse response1 = transactionService.initiateTransfer(userId, idempotencyKey, request);
        TransactionResponse response2 = transactionService.initiateTransfer(userId, idempotencyKey, request);

        assertEquals(response1.getTransactionId(), response2.getTransactionId());
        assertEquals(response1.getStatus(), response2.getStatus());

        // Verify DB only has 1 transaction record
        assertEquals(1, transactionRepository.count());
        // Verify ledger client was only called once
        verify(ledgerServiceClient, times(1)).processDoubleEntry(any());
    }

    @Test
    @DisplayName("Fraud check rejection stops Saga immediately without touching Ledger Service")
    void initiateTransfer_FraudRejected_NoLedgerCall() {
        when(fraudServiceClient.evaluateRisk(any())).thenReturn(
                FraudCheckResponse.builder()
                        .approved(false)
                        .riskLevel("HIGH")
                        .reason("FRAUD_SUSPECTED")
                        .build()
        );

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("99000000.00"))
                .currency("VND")
                .note("Suspicious high amount")
                .build();

        assertThrows(FraudRejectedException.class, () -> transactionService.initiateTransfer(userId, idempotencyKey, request));

        // Verify Ledger Service was NEVER called
        verify(ledgerServiceClient, never()).processDoubleEntry(any());

        // Verify transaction is marked as FAILED with reason FRAUD_SUSPECTED
        Transaction tx = transactionRepository.findByClientIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(TransactionStatus.FAILED, tx.getStatus());
        assertEquals("FRAUD_SUSPECTED", tx.getFailureReason());

        // Verify Outbox recorded TransactionFailed event
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals("TransactionFailed", events.get(0).getEventType());
    }

    @Test
    @DisplayName("Fraud service outage causes fail-closed rejection without processing transaction")
    void initiateTransfer_FraudServiceUnavailable_FailClosed() {
        when(fraudServiceClient.evaluateRisk(any())).thenThrow(new RuntimeException("Connection timed out to fraud service"));

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .build();

        assertThrows(FraudServiceUnavailableException.class, () -> transactionService.initiateTransfer(userId, idempotencyKey, request));

        verify(ledgerServiceClient, never()).processDoubleEntry(any());

        Transaction tx = transactionRepository.findByClientIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(TransactionStatus.FAILED, tx.getStatus());
        assertEquals("FRAUD_SERVICE_UNAVAILABLE", tx.getFailureReason());
    }

    @Test
    @DisplayName("Ledger Insufficient Balance rejects transaction cleanly without compensation")
    void initiateTransfer_InsufficientBalance_FailsCleanly() {
        feign.Request feignRequest = feign.Request.create(
                feign.Request.HttpMethod.POST,
                "/internal/v1/ledger/entries",
                java.util.Collections.emptyMap(),
                null,
                java.nio.charset.StandardCharsets.UTF_8,
                null
        );

        when(ledgerServiceClient.processDoubleEntry(any())).thenThrow(
                new feign.FeignException.UnprocessableEntity(
                        "INSUFFICIENT_BALANCE",
                        feignRequest,
                        "{\"status\":\"FAILED\",\"reason\":\"INSUFFICIENT_BALANCE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.util.Collections.emptyMap()
                )
        );

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("50000000.00"))
                .currency("VND")
                .build();

        assertThrows(InsufficientBalanceException.class, () -> transactionService.initiateTransfer(userId, idempotencyKey, request));

        Transaction tx = transactionRepository.findByClientIdempotencyKey(idempotencyKey).orElseThrow();
        assertEquals(TransactionStatus.FAILED, tx.getStatus());
        assertEquals("INSUFFICIENT_BALANCE", tx.getFailureReason());

        // Verify reversal was NOT called because ledger had nothing to compensate
        verify(ledgerServiceClient, never()).processReversal(any());
    }

    @Test
    @DisplayName("Transient ledger failure is retried with deterministic idempotency key and succeeds on retry")
    void initiateTransfer_LedgerRetrySuccess() {
        // Attempt 1: FeignException (transient network error)
        // Attempt 2: 200 OK
        when(ledgerServiceClient.processDoubleEntry(any()))
                .thenThrow(new RuntimeException("Socket timeout calling ledger"))
                .thenReturn(ResponseEntity.ok(CreateLedgerEntryClientResponse.builder()
                        .debitEntryId(UUID.randomUUID())
                        .creditEntryId(UUID.randomUUID())
                        .status("COMPLETED")
                        .build()));

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .build();

        TransactionResponse response = transactionService.initiateTransfer(userId, idempotencyKey, request);

        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        // Verify ledger was called twice
        verify(ledgerServiceClient, times(2)).processDoubleEntry(any());
    }

    @Test
    @DisplayName("Compensation flow: Reversal is executed and status transitions to COMPENSATED")
    void compensationFlow_Success() {
        Transaction tx = Transaction.builder()
                .clientIdempotencyKey(UUID.randomUUID().toString())
                .userId(userId)
                .fromAccountId(userAccountId)
                .toAccountId(recipientAccountId)
                .amount(new BigDecimal("300000.00"))
                .currency("VND")
                .type(TransactionType.WITHDRAW)
                .status(TransactionStatus.PROCESSING)
                .build();
        tx = transactionRepository.save(tx);

        sagaOrchestrator.compensateTransaction(tx, "EXTERNAL_GATEWAY_TIMEOUT");

        Transaction updated = transactionRepository.findById(tx.getId()).orElseThrow();
        assertEquals(TransactionStatus.COMPENSATED, updated.getStatus());
        assertEquals("EXTERNAL_GATEWAY_TIMEOUT", updated.getFailureReason());

        // Verify reversal was called
        verify(ledgerServiceClient, times(1)).processReversal(argThat(req ->
                req.getOriginalTransactionId().equals(updated.getId()) &&
                req.getIdempotencyKey().equals(IdempotencyManager.getLedgerReversalKey(updated.getId()))
        ));

        // Verify outbox recorded TransactionCompensated
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());
        assertEquals("TransactionCompensated", events.get(0).getEventType());
    }

    @Test
    @DisplayName("Stuck Transaction Reaper identifies and recovers stalled transactions")
    void stuckTransactionReaper_RecoversStalledTransaction() {
        // Seed a transaction stalled in PROCESSING with updatedAt 5 minutes ago
        Transaction stuckTx = Transaction.builder()
                .clientIdempotencyKey(UUID.randomUUID().toString())
                .userId(userId)
                .fromAccountId(userAccountId)
                .toAccountId(recipientAccountId)
                .amount(new BigDecimal("250000.00"))
                .currency("VND")
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PROCESSING)
                .createdAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .updatedAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                .build();
        stuckTx = transactionRepository.saveAndFlush(stuckTx);

        // Run reaper
        stuckTransactionReaper.reapStuckTransactions();

        // Verify transaction is recovered and completed
        Transaction recovered = transactionRepository.findById(stuckTx.getId()).orElseThrow();
        assertEquals(TransactionStatus.COMPLETED, recovered.getStatus());
    }

    @Test
    @DisplayName("Authorization check: Non-owner user cannot access another user's transaction (403 Forbidden)")
    void getTransactionById_NonOwner_Forbidden() {
        UUID otherUserId = UUID.randomUUID();
        Transaction tx = Transaction.builder()
                .clientIdempotencyKey(UUID.randomUUID().toString())
                .userId(otherUserId)
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .build();
        tx = transactionRepository.save(tx);

        final UUID txId = tx.getId();
        assertThrows(UnauthorizedTransactionAccessException.class, () ->
                transactionService.getTransactionById(userId, "ROLE_USER", txId)
        );

        // Admin can access
        TransactionResponse adminAccess = transactionService.getTransactionById(userId, "ROLE_ADMIN", txId);
        assertNotNull(adminAccess);
    }

    @Test
    @DisplayName("Deposit flow transfers funds from system suspense account to user account")
    void initiateDeposit_Success() {
        String idempotencyKey = UUID.randomUUID().toString();
        DepositRequest request = DepositRequest.builder()
                .amount(new BigDecimal("1000000.00"))
                .currency("VND")
                .note("Topup from VNPAY")
                .build();

        TransactionResponse response = transactionService.initiateDeposit(userId, idempotencyKey, request);

        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        assertEquals(TransactionType.DEPOSIT, response.getType());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), response.getFromAccountId());
        assertEquals(userAccountId, response.getToAccountId());
    }

    @Test
    @DisplayName("Withdrawal flow transfers funds from user account to system suspense account")
    void initiateWithdraw_Success() {
        String idempotencyKey = UUID.randomUUID().toString();
        WithdrawRequest request = WithdrawRequest.builder()
                .amount(new BigDecimal("400000.00"))
                .currency("VND")
                .bankAccountNumber("987654321")
                .bankCode("VCB")
                .note("Cash out to bank")
                .build();

        TransactionResponse response = transactionService.initiateWithdraw(userId, idempotencyKey, request);

        assertEquals(TransactionStatus.COMPLETED, response.getStatus());
        assertEquals(TransactionType.WITHDRAW, response.getType());
        assertEquals(userAccountId, response.getFromAccountId());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000001"), response.getToAccountId());
    }

    @Test
    @DisplayName("OutboxPublisher polls unpublished events and dispatches to Kafka")
    void outboxPublisher_DispatchesSuccessfully() {
        outboxEventRepository.deleteAll();

        String idempotencyKey = UUID.randomUUID().toString();
        TransferRequest request = TransferRequest.builder()
                .toAccountNumber(recipientAccountNumber)
                .amount(new BigDecimal("75000.00"))
                .currency("VND")
                .build();

        transactionService.initiateTransfer(userId, idempotencyKey, request);

        List<OutboxEvent> eventsBefore = outboxEventRepository.findAll();
        assertEquals(1, eventsBefore.size());
        assertFalse(eventsBefore.get(0).getPublished());

        outboxPublisher.publishUnpublishedEvents();

        OutboxEvent dispatched = outboxEventRepository.findById(eventsBefore.get(0).getId()).orElseThrow();
        assertTrue(dispatched.getPublished());
    }
}
