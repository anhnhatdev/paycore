package com.paycore.transactionservice.reaper;

import com.paycore.transactionservice.domain.entity.Transaction;
import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.repository.TransactionRepository;
import com.paycore.transactionservice.service.SagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Stuck Transaction Reaper.
 * <p>
 * Background scheduled job that identifies and recovers stalled transactions
 * resulting from node crashes or unhandled timeouts during Saga execution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckTransactionReaper {

    private final TransactionRepository transactionRepository;
    private final SagaOrchestrator sagaOrchestrator;

    @Value("${transaction.saga.stuck-reaper-threshold-seconds:120}")
    private long stuckThresholdSeconds;

    @Scheduled(cron = "${transaction.saga.stuck-reaper-cron:0 * * * * *}")
    public void reapStuckTransactions() {
        Instant threshold = Instant.now().minus(Duration.ofSeconds(stuckThresholdSeconds));
        List<TransactionStatus> stuckStatuses = List.of(
                TransactionStatus.PENDING,
                TransactionStatus.PROCESSING,
                TransactionStatus.COMPENSATING
        );

        List<Transaction> stuckList = transactionRepository.findStuckTransactions(stuckStatuses, threshold);
        if (stuckList.isEmpty()) {
            return;
        }

        log.warn("StuckTransactionReaper detected {} stalled transaction(s)", stuckList.size());

        for (Transaction tx : stuckList) {
            try {
                if (tx.getStatus() == TransactionStatus.COMPENSATING) {
                    log.error("ALERT: Re-attempting compensation for critical stuck transaction: id={}", tx.getId());
                    sagaOrchestrator.compensateTransaction(tx, tx.getFailureReason() != null ? tx.getFailureReason() : "STUCK_IN_COMPENSATION");
                } else {
                    log.info("Resuming stalled Saga for transaction: id={}, status={}", tx.getId(), tx.getStatus());
                    sagaOrchestrator.executeSaga(tx);
                }
            } catch (Exception e) {
                log.error("Failed to recover stuck transaction {}: {}", tx.getId(), e.getMessage(), e);
            }
        }
    }
}
