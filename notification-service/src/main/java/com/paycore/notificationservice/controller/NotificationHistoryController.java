package com.paycore.notificationservice.controller;

import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.repository.NotificationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification History & Audit", description = "Internal mTLS endpoints for querying notification delivery logs")
public class NotificationHistoryController {

    private final NotificationLogRepository notificationLogRepository;

    @GetMapping("/history/{userId}")
    @Operation(summary = "Get user notification audit history", description = "Retrieves delivery logs with status and masked recipients for audit purposes")
    public ResponseEntity<List<NotificationLog>> getUserNotificationHistory(@PathVariable("userId") UUID userId) {
        log.info("REST: Querying notification history for userId: {}", userId);
        return ResponseEntity.ok(notificationLogRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }
}
