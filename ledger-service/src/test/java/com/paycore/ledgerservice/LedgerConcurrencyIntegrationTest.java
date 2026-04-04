package com.paycore.ledgerservice;

import com.paycore.ledgerservice.domain.entity.Balance;
import com.paycore.ledgerservice.dto.CreateLedgerEntryRequest;
import com.paycore.ledgerservice.exception.InsufficientBalanceException;
import com.paycore.ledgerservice.repository.BalanceRepository;
import com.paycore.ledgerservice.repository.IdempotencyKeyRepository;
import com.paycore.ledgerservice.repository.LedgerEntryRepository;
import com.paycore.ledgerservice.repository.SystemAccountRepository;
import com.paycore.ledgerservice.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class LedgerConcurrencyIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private SystemAccountRepository systemAccountRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        balanceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();

        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();

        // Account A has 1,000,000 VND
        Balance balA = Balance.builder()
                .accountId(accountA)
                .currency("VND")
                .availableBalance(new BigDecimal("1000000.00"))
                .pendingBalance(BigDecimal.ZERO)
                .build();
        balanceRepository.save(balA);

        // Account B has 0 VND
        Balance balB = Balance.builder()
                .accountId(accountB)
                .currency("VND")
                .availableBalance(BigDecimal.ZERO)
                .pendingBalance(BigDecimal.ZERO)
                .build();
        balanceRepository.save(balB);
    }

    @Test
    @DisplayName("Concurrent transfers exceeding balance: Exactly one succeeds, no overdraft occurs")
    void concurrentTransfers_PreventOverdraft() throws InterruptedException {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Two concurrent requests, each asking for 800,000 VND from 1,000,000 VND balance
        BigDecimal transferAmount = new BigDecimal("800000.00");

        Callable<Void> task1 = () -> {
            latch.await();
            try {
                ledgerService.processDoubleEntry(CreateLedgerEntryRequest.builder()
                        .transactionId(UUID.randomUUID())
                        .idempotencyKey(UUID.randomUUID().toString())
                        .debitAccountId(accountA)
                        .creditAccountId(accountB)
                        .amount(transferAmount)
                        .currency("VND")
                        .build());
                successCount.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            latch.await();
            try {
                ledgerService.processDoubleEntry(CreateLedgerEntryRequest.builder()
                        .transactionId(UUID.randomUUID())
                        .idempotencyKey(UUID.randomUUID().toString())
                        .debitAccountId(accountA)
                        .creditAccountId(accountB)
                        .amount(transferAmount)
                        .currency("VND")
                        .build());
                successCount.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                failCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task1);
        Future<Void> f2 = executor.submit(task2);

        // Start both simultaneously
        latch.countDown();

        try {
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}

        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly 1 transaction must succeed");
        assertEquals(1, failCount.get(), "Exactly 1 transaction must fail due to insufficient balance");

        // Verify final balance invariant: total system balance = 1,000,000
        Balance finalA = balanceRepository.findById(accountA).orElseThrow();
        Balance finalB = balanceRepository.findById(accountB).orElseThrow();

        assertEquals(new BigDecimal("200000.00"), finalA.getAvailableBalance());
        assertEquals(new BigDecimal("800000.00"), finalB.getAvailableBalance());
        assertEquals(new BigDecimal("1000000.00"), finalA.getAvailableBalance().add(finalB.getAvailableBalance()));
    }
}
