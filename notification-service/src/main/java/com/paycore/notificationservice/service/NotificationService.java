package com.paycore.notificationservice.service;

import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.dto.KafkaEventEnvelope;

import java.util.UUID;

public interface NotificationService {

    /**
     * Executes the strict 8-step idempotent notification pipeline for an incoming Kafka event.
     * @return true if processed or deduplicated, false if error occurred requiring retry
     */
    boolean processEvent(KafkaEventEnvelope event);

    /**
     * Direct dispatch of a single notification log (used by retry daemon and recovery daemon).
     */
    void dispatchNotification(NotificationLog logEntry, String plainRecipient);
}
