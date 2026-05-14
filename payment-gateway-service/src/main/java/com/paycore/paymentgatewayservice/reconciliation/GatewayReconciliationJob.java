package com.paycore.paymentgatewayservice.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderAdapter;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderFactory;
import com.paycore.paymentgatewayservice.adapter.dto.ProviderQueryStatusResult;
import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.entity.OutboxEvent;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.repository.GatewayTransactionRepository;
import com.paycore.paymentgatewayservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayReconciliationJob {

    private final GatewayTransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Value("${payment.gateway.reconcile.stuck-threshold-seconds:300}")
    private long stuckThresholdSeconds;

    /**
     * Periodically queries payment providers to reconcile pending transactions that missed webhooks.
     */
    @Scheduled(cron = "${payment.gateway.reconcile.cron:0 */5 * * * *}")
    @Transactional
    public void reconcilePendingTransactions() {
        Instant threshold = Instant.now().minus(Duration.ofSeconds(stuckThresholdSeconds));
        List<GatewayTransaction> pendingTxs = transactionRepository.findPendingTransactionsOlderThan(
                GatewayTransactionStatus.PENDING_PROVIDER, threshold
        );

        if (pendingTxs.isEmpty()) {
            return;
        }

        log.info("Gateway reconciliation job found {} pending transactions to reconcile", pendingTxs.size());

        for (GatewayTransaction tx : pendingTxs) {
            try {
                reconcileSingleTransaction(tx);
            } catch (Exception e) {
                log.error("Failed to reconcile gateway tx: id={}", tx.getId(), e);
            }
        }
    }

    /**
     * Checks and expires transactions that reached expiration time without being paid.
     */
    @Scheduled(cron = "${payment.gateway.reconcile.expiration-cron:0 */2 * * * *}")
    @Transactional
    public void expireStaleTransactions() {
        List<GatewayTransaction> expiredTxs = transactionRepository.findExpiredPendingTransactions(Instant.now());
        if (expiredTxs.isEmpty()) {
            return;
        }

        log.info("Expiring {} pending transactions that passed expiration time", expiredTxs.size());

        for (GatewayTransaction tx : expiredTxs) {
            try {
                tx.setStatus(GatewayTransactionStatus.EXPIRED);
                transactionRepository.saveAndFlush(tx);

                recordOutboxEvent(tx.getInternalTransactionId(), "GatewayPaymentExpired", Map.of(
                        "gatewayTransactionId", tx.getId(),
                        "internalTransactionId", tx.getInternalTransactionId(),
                        "provider", tx.getProvider().name(),
                        "status", "EXPIRED",
                        "reason", "Transaction expired before completion"
                ));
                log.info("Transaction marked as EXPIRED: id={}, internalTxId={}", tx.getId(), tx.getInternalTransactionId());
            } catch (Exception e) {
                log.error("Failed to expire transaction: id={}", tx.getId(), e);
            }
        }
    }

    public void reconcileSingleTransaction(GatewayTransaction tx) {
        PaymentProviderAdapter adapter = providerFactory.getAdapter(tx.getProvider());
        ProviderQueryStatusResult queryResult = adapter.queryTransactionStatus(tx);

        if (queryResult.getStatus() == GatewayTransactionStatus.SUCCEEDED) {
            log.info("Reconciliation recovered successful transaction: id={}, providerRef={}",
                    tx.getId(), tx.getProviderTransactionRef());
            tx.setStatus(GatewayTransactionStatus.SUCCEEDED);
            transactionRepository.saveAndFlush(tx);

            recordOutboxEvent(tx.getInternalTransactionId(), "GatewayPaymentSucceeded", Map.of(
                    "gatewayTransactionId", tx.getId(),
                    "internalTransactionId", tx.getInternalTransactionId(),
                    "provider", tx.getProvider().name(),
                    "providerTransactionRef", tx.getProviderTransactionRef() != null ? tx.getProviderTransactionRef() : "",
                    "amount", tx.getAmount(),
                    "currency", tx.getCurrency(),
                    "status", "SUCCEEDED",
                    "origin", "RECONCILE"
            ));
        } else if (queryResult.getStatus() == GatewayTransactionStatus.FAILED) {
            log.info("Reconciliation confirmed failed transaction: id={}", tx.getId());
            tx.setStatus(GatewayTransactionStatus.FAILED);
            transactionRepository.saveAndFlush(tx);

            recordOutboxEvent(tx.getInternalTransactionId(), "GatewayPaymentFailed", Map.of(
                    "gatewayTransactionId", tx.getId(),
                    "internalTransactionId", tx.getInternalTransactionId(),
                    "provider", tx.getProvider().name(),
                    "status", "FAILED",
                    "reason", queryResult.getMessage() != null ? queryResult.getMessage() : "Payment rejected upon reconciliation",
                    "origin", "RECONCILE"
            ));
        }
    }

    private void recordOutboxEvent(UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .published(false)
                    .build();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to serialize outbox payload for aggregateId: {}", aggregateId, e);
        }
    }
}
