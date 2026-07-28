package com.paycore.auditservice.scheduler;

import com.paycore.auditservice.service.AuditArchivalService;
import com.paycore.auditservice.service.AuditCheckpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditScheduler {

    private final AuditCheckpointService checkpointService;
    private final AuditArchivalService archivalService;

    @Scheduled(cron = "${paycore.audit.checkpoint.cron:0 0 23 * * *}")
    public void scheduleDailyCheckpoint() {
        log.info("Cron trigger: Starting daily hash checkpoint generation");
        checkpointService.createCheckpoint("DAILY_COMPLIANCE_DIGEST");
    }

    @Scheduled(cron = "${paycore.audit.archival.cron:0 0 3 1 * *}")
    public void scheduleMonthlyArchival() {
        log.info("Cron trigger: Starting monthly partition archival");
        archivalService.archiveOldPartitions();
    }
}
