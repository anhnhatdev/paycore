package com.paycore.notificationservice.daemon;

import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.domain.enums.NotificationStatus;
import com.paycore.notificationservice.repository.NotificationLogRepository;
import com.paycore.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryDaemon {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationService notificationService;
    private final DeadLetterPublisher deadLetterPublisher;

    @Value("${notification.retry.max-attempts:3}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${notification.retry.interval-ms:10000}", initialDelay = 5000)
    public void retryFailedNotifications() {
        List<NotificationLog> failedLogs = notificationLogRepository
                .findByStatusAndAttemptCountLessThanOrderByCreatedAtAsc(
                        NotificationStatus.FAILED,
                        maxAttempts,
                        PageRequest.of(0, 50)
                );

        if (failedLogs.isEmpty()) {
            return;
        }

        log.info("NotificationRetryDaemon: Found {} failed notifications to retry", failedLogs.size());

        for (NotificationLog logEntry : failedLogs) {
            try {
                notificationService.dispatchNotification(logEntry, null);
                log.info("Retry SUCCESS for notification: id={}", logEntry.getId());
            } catch (Exception e) {
                log.warn("Retry ATTEMPT #{} FAILED for notification {}: {}",
                        logEntry.getAttemptCount(), logEntry.getId(), e.getMessage());

                if (logEntry.getAttemptCount() >= maxAttempts) {
                    logEntry.setStatus(NotificationStatus.DEAD_LETTER);
                    notificationLogRepository.save(logEntry);
                    deadLetterPublisher.publishDeadLetter(logEntry);
                    log.error("Notification {} exceeded max attempts ({}), transitioned to DEAD_LETTER",
                            logEntry.getId(), maxAttempts);
                }
            }
        }
    }
}
