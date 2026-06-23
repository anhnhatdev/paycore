package com.paycore.notificationservice.daemon;

import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.repository.NotificationLogRepository;
import com.paycore.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StuckPendingRecoveryDaemon {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationService notificationService;

    @Value("${notification.pending-recovery.stuck-threshold-minutes:5}")
    private int stuckThresholdMinutes;

    @Scheduled(fixedDelayString = "${notification.pending-recovery.interval-ms:60000}", initialDelay = 10000)
    public void recoverStuckPendingNotifications() {
        Instant stuckBefore = Instant.now().minus(Duration.ofMinutes(stuckThresholdMinutes));
        List<NotificationLog> stuckLogs = notificationLogRepository
                .findStuckPendingNotifications(stuckBefore, PageRequest.of(0, 50));

        if (stuckLogs.isEmpty()) {
            return;
        }

        log.warn("StuckPendingRecoveryDaemon: Found {} stuck PENDING notifications (older than {}m) to recover",
                stuckLogs.size(), stuckThresholdMinutes);

        for (NotificationLog logEntry : stuckLogs) {
            try {
                notificationService.dispatchNotification(logEntry, null);
                log.info("Successfully recovered stuck notification: id={}", logEntry.getId());
            } catch (Exception e) {
                log.error("Failed to recover stuck notification {}: {}", logEntry.getId(), e.getMessage());
            }
        }
    }
}
