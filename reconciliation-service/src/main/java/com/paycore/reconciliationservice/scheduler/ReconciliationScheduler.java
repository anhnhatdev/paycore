package com.paycore.reconciliationservice.scheduler;

import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    /**
     * Periodic per-account consistency check (hourly).
     */
    @Scheduled(cron = "${paycore.scheduler.per-account-cron:0 0 * * * *}")
    public void schedulePerAccountReconciliation() {
        log.info("Cron trigger: Starting INTERNAL_PER_ACCOUNT reconciliation");
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));
        reconciliationService.executeReconciliation(ReconciliationRunType.INTERNAL_PER_ACCOUNT, start, end);
    }

    /**
     * Periodic global invariant check (every 6 hours).
     */
    @Scheduled(cron = "${paycore.scheduler.global-invariant-cron:0 0 */6 * * *}")
    public void scheduleGlobalInvariantReconciliation() {
        log.info("Cron trigger: Starting INTERNAL_GLOBAL_INVARIANT reconciliation");
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(6));
        reconciliationService.executeReconciliation(ReconciliationRunType.INTERNAL_GLOBAL_INVARIANT, start, end);
    }

    /**
     * Periodic cross-service check: Transaction ↔ Ledger (every 2 hours).
     */
    @Scheduled(cron = "${paycore.scheduler.cross-service-cron:0 30 */2 * * *}")
    public void scheduleCrossServiceReconciliation() {
        log.info("Cron trigger: Starting CROSS_SERVICE reconciliation");
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(2));
        reconciliationService.executeReconciliation(ReconciliationRunType.CROSS_SERVICE, start, end);
    }

    /**
     * Daily external gateway settlement reconciliation (2:00 AM daily for T-1).
     */
    @Scheduled(cron = "${paycore.scheduler.external-gateway-cron:0 0 2 * * *}")
    public void scheduleExternalGatewayReconciliation() {
        log.info("Cron trigger: Starting EXTERNAL_GATEWAY reconciliation");
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(1));
        reconciliationService.executeReconciliation(ReconciliationRunType.EXTERNAL_GATEWAY, start, end);
    }
}
