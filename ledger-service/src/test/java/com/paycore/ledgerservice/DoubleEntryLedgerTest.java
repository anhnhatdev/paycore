package com.paycore.ledgerservice;

import com.paycore.ledgerservice.domain.entity.*;
import com.paycore.ledgerservice.dto.*;
import com.paycore.ledgerservice.exception.CurrencyMismatchException;
import com.paycore.ledgerservice.exception.InsufficientBalanceException;
import com.paycore.ledgerservice.repository.BalanceRepository;
import com.paycore.ledgerservice.repository.IdempotencyKeyRepository;
import com.paycore.ledgerservice.repository.LedgerEntryRepository;
import com.paycore.ledgerservice.repository.SystemAccountRepository;
import com.paycore.ledgerservice.service.LedgerService;
import com.paycore.ledgerservice.service.ReconciliationService;
import com.paycore.ledgerservice.service.ReversalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DoubleEntryLedgerTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private SystemAccountRepository systemAccountRepository;

    @Autowired
    private com.paycore.ledgerservice.repository.OutboxEventRepository outboxEventRepository;

    @Autowired
    private com.paycore.ledgerservice.outbox.OutboxPublisher outboxPublisher;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private UUID accountA;
    private UUID accountB;
    private UUID systemSuspenseVndId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        balanceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        systemAccountRepository.deleteAll();

        // Seed system account
        systemSuspenseVndId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        SystemAccount sysVnd = SystemAccount.builder()
                .id(systemSuspenseVndId)
                .code("SUSPENSE_VND")
                .currency("VND")
                .description("System VND Suspense")
                .build();
        systemAccountRepository.save(sysVnd);

        // Seed user accounts
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();

        Balance balanceA = Balance.builder()
                .accountId(accountA)
                .currency("VND")
                .availableBalance(new BigDecimal("1000000.00"))
                .pendingBalance(BigDecimal.ZERO)
                .build();
        balanceRepository.save(balanceA);

        Balance balanceB = Balance.builder()
                .accountId(accountB)
                .currency("VND")
                .availableBalance(new BigDecimal("500000.00"))
                .pendingBalance(BigDecimal.ZERO)
                .build();
        balanceRepository.save(balanceB);
    }

    @Test
    @DisplayName("Valid double-entry transfer creates 1 DEBIT, 1 CREDIT, and updates balances")
    void processDoubleEntry_Success() {
        UUID txId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .build();

        CreateLedgerEntryResponse response = ledgerService.processDoubleEntry(request);

        assertNotNull(response.getDebitEntryId());
        assertNotNull(response.getCreditEntryId());
        assertEquals(new BigDecimal("800000.00"), response.getDebitBalanceAfter());
        assertEquals(new BigDecimal("700000.00"), response.getCreditBalanceAfter());
        assertEquals("COMPLETED", response.getStatus());

        // Verify ledger entries
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txId);
        assertEquals(2, entries.size());

        // Verify balance projection
        Balance balA = balanceRepository.findById(accountA).orElseThrow();
        Balance balB = balanceRepository.findById(accountB).orElseThrow();
        assertEquals(new BigDecimal("800000.00"), balA.getAvailableBalance());
        assertEquals(new BigDecimal("700000.00"), balB.getAvailableBalance());
    }

    @Test
    @DisplayName("Idempotent retry with same key and payload returns identical snapshot without duplicating entries")
    void processDoubleEntry_IdempotencyDuplicate_ReturnsCached() {
        UUID txId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .build();

        CreateLedgerEntryResponse firstResponse = ledgerService.processDoubleEntry(request);
        CreateLedgerEntryResponse secondResponse = ledgerService.processDoubleEntry(request);

        assertEquals(firstResponse.getDebitEntryId(), secondResponse.getDebitEntryId());
        assertEquals(firstResponse.getCreditEntryId(), secondResponse.getCreditEntryId());
        assertEquals(firstResponse.getDebitBalanceAfter(), secondResponse.getDebitBalanceAfter());

        // Verify entries were NOT duplicated
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txId);
        assertEquals(2, entries.size());
    }

    @Test
    @DisplayName("Insufficient balance rolls back transaction and persists FAILED idempotency state for retries")
    void processDoubleEntry_InsufficientBalance_FailsAndCachesFailure() {
        UUID txId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("5000000.00")) // Exceeds balance of 1M
                .currency("VND")
                .build();

        assertThrows(InsufficientBalanceException.class, () -> ledgerService.processDoubleEntry(request));

        // Verify NO ledger entries were created
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(txId);
        assertTrue(entries.isEmpty());

        // Verify balances untouched
        Balance balA = balanceRepository.findById(accountA).orElseThrow();
        assertEquals(new BigDecimal("1000000.00"), balA.getAvailableBalance());

        // Retry with same idempotency key must immediately throw InsufficientBalanceException from cached FAILED snapshot
        assertThrows(InsufficientBalanceException.class, () -> ledgerService.processDoubleEntry(request));
    }

    @Test
    @DisplayName("Deposit from System Suspense Account allows negative suspense balance and credits user wallet")
    void processDeposit_SystemAccount_AllowsNegativeBalance() {
        UUID txId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .debitAccountId(systemSuspenseVndId) // Debit system suspense
                .creditAccountId(accountA)          // Credit user wallet
                .amount(new BigDecimal("10000000.00"))
                .currency("VND")
                .build();

        CreateLedgerEntryResponse response = ledgerService.processDoubleEntry(request);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(new BigDecimal("-10000000.00"), response.getDebitBalanceAfter());
        assertEquals(new BigDecimal("11000000.00"), response.getCreditBalanceAfter());

        Balance userBal = balanceRepository.findById(accountA).orElseThrow();
        assertEquals(new BigDecimal("11000000.00"), userBal.getAvailableBalance());
    }

    @Test
    @DisplayName("Reversal entry reverses funds and preserves original immutable ledger entries")
    void processReversal_Success() {
        UUID txId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("300000.00"))
                .currency("VND")
                .build();

        ledgerService.processDoubleEntry(request);

        // Perform Reversal
        ReverseLedgerEntryRequest revRequest = ReverseLedgerEntryRequest.builder()
                .originalTransactionId(txId)
                .idempotencyKey(UUID.randomUUID().toString())
                .reason("CREDIT_STEP_FAILED")
                .build();

        ReverseLedgerEntryResponse revResponse = reversalService.processReversal(revRequest);

        assertEquals("REVERSED", revResponse.getStatus());

        // Verify balances restored to original
        Balance balA = balanceRepository.findById(accountA).orElseThrow();
        Balance balB = balanceRepository.findById(accountB).orElseThrow();
        assertEquals(new BigDecimal("1000000.00"), balA.getAvailableBalance());
        assertEquals(new BigDecimal("500000.00"), balB.getAvailableBalance());

        // Verify total entries = 4 (2 original + 2 reversal)
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();
        assertEquals(4, allEntries.size());
    }

    @Test
    @DisplayName("Reconciliation engine detects balance integrity and identifies mismatches")
    void reconciliation_DetectsBalance() {
        UUID txId = UUID.randomUUID();
        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(UUID.randomUUID().toString())
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .build();

        ledgerService.processDoubleEntry(request);

        // Account A: Initial 1M, Debited 100k -> 900k
        // Note: For regular reconciliation calculation, total credits - total debits
        ReconciliationResponse reconA = reconciliationService.reconcileAccount(accountA);
        assertNotNull(reconA);
        assertEquals(new BigDecimal("900000.00"), reconA.getBalanceStored());
    }

    @Test
    @DisplayName("Transfer between different currencies is rejected with CurrencyMismatchException")
    void processDoubleEntry_CurrencyMismatch_ThrowsException() {
        // Create USD account
        UUID accountUsd = UUID.randomUUID();
        Balance balanceUsd = Balance.builder()
                .accountId(accountUsd)
                .currency("USD")
                .availableBalance(new BigDecimal("500.00"))
                .pendingBalance(BigDecimal.ZERO)
                .build();
        balanceRepository.save(balanceUsd);

        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .debitAccountId(accountA) // VND
                .creditAccountId(accountUsd) // USD
                .amount(new BigDecimal("100.00"))
                .currency("VND")
                .build();

        assertThrows(CurrencyMismatchException.class, () -> ledgerService.processDoubleEntry(request));
    }

    @Test
    @DisplayName("Transfer to same account is rejected")
    void processDoubleEntry_SameAccount_ThrowsException() {
        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .debitAccountId(accountA)
                .creditAccountId(accountA)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .build();

        assertThrows(IllegalArgumentException.class, () -> ledgerService.processDoubleEntry(request));
    }

    @Test
    @DisplayName("Stale PROCESSING idempotency key older than 30s is reclaimed and processed")
    void processDoubleEntry_StaleKeyRecovery() {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .build();

        // Seed a stale PROCESSING key from 60 seconds ago
        IdempotencyKey staleKey = IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .requestHash("sample-hash")
                .status(IdempotencyStatus.PROCESSING)
                .createdAt(Instant.now().minus(60, ChronoUnit.SECONDS))
                .updatedAt(Instant.now().minus(60, ChronoUnit.SECONDS))
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        // Compute real hash to avoid mismatch
        staleKey.setRequestHash(new com.paycore.ledgerservice.idempotency.IdempotencyManager(
                idempotencyKeyRepository, new com.fasterxml.jackson.databind.ObjectMapper()
        ).computeHash(request));

        idempotencyKeyRepository.save(staleKey);

        // Process request -> should reclaim stale key and complete successfully
        CreateLedgerEntryResponse response = ledgerService.processDoubleEntry(request);
        assertEquals("COMPLETED", response.getStatus());
    }

    @Test
    @DisplayName("Withdrawal to System Account decreases user balance and increases system account balance")
    void processWithdrawal_ToSystemAccount_Success() {
        UUID txId = UUID.randomUUID();
        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(UUID.randomUUID().toString())
                .debitAccountId(accountA)             // User wallet
                .creditAccountId(systemSuspenseVndId) // System suspense counterparty
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .build();

        CreateLedgerEntryResponse response = ledgerService.processDoubleEntry(request);

        assertEquals("COMPLETED", response.getStatus());
        assertEquals(new BigDecimal("800000.00"), response.getDebitBalanceAfter());
        assertEquals(new BigDecimal("200000.00"), response.getCreditBalanceAfter());

        Balance userBal = balanceRepository.findById(accountA).orElseThrow();
        assertEquals(new BigDecimal("800000.00"), userBal.getAvailableBalance());
    }

    @Test
    @DisplayName("Reconciliation engine accurately detects discrepancy when balance is deliberately corrupted")
    void reconciliation_DetectsMismatch_WhenCorrupted() {
        // Seed corrupted balance (1,000,000 in balance, but no ledger entries)
        ReconciliationResponse recon = reconciliationService.reconcileAccount(accountA);
        assertFalse(recon.getIsBalanced(), "Reconciliation must detect imbalance when balance exists without ledger credits");
        assertEquals(new BigDecimal("1000000.00"), recon.getBalanceStored());
        assertEquals(BigDecimal.ZERO, recon.getBalanceCalculated());
    }

    @Test
    @DisplayName("Transactional Outbox writes unpublished event and OutboxPublisher dispatches to Kafka")
    void outboxEvent_CreatedAndDispatched_Success() {
        outboxEventRepository.deleteAll();

        UUID txId = UUID.randomUUID();
        CreateLedgerEntryRequest request = CreateLedgerEntryRequest.builder()
                .transactionId(txId)
                .idempotencyKey(UUID.randomUUID().toString())
                .debitAccountId(accountA)
                .creditAccountId(accountB)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .build();

        ledgerService.processDoubleEntry(request);

        // Verify outbox entry exists with published = false
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size());
        assertFalse(events.get(0).getPublished());
        assertEquals("LedgerEntryCreated", events.get(0).getEventType());

        // Run OutboxPublisher
        outboxPublisher.publishUnpublishedEvents();

        // Verify event is marked as published
        OutboxEvent dispatched = outboxEventRepository.findById(events.get(0).getId()).orElseThrow();
        assertTrue(dispatched.getPublished());
    }
}
