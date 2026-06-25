package com.paycore.notificationservice.controller;

import com.paycore.notificationservice.domain.entity.NotificationPreference;
import com.paycore.notificationservice.domain.entity.NotificationPreferenceId;
import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.domain.enums.NotificationEventType;
import com.paycore.notificationservice.repository.NotificationPreferenceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications/preferences")
@RequiredArgsConstructor
@Tag(name = "User Notification Preferences", description = "Endpoints for configuring communication preferences per event type and channel")
public class NotificationPreferenceController {

    private final NotificationPreferenceRepository preferenceRepository;

    @GetMapping
    @Operation(summary = "Get user preferences", description = "Retrieves all custom notification preferences for the authenticated user")
    public ResponseEntity<List<NotificationPreference>> getPreferences(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000000") UUID userId
    ) {
        return ResponseEntity.ok(preferenceRepository.findByIdUserId(userId));
    }

    @PutMapping
    @Operation(summary = "Update user preference", description = "Sets enabled status for specific event type and channel")
    public ResponseEntity<NotificationPreference> updatePreference(
            @RequestHeader(value = "X-User-Id", defaultValue = "00000000-0000-0000-0000-000000000000") UUID userId,
            @Valid @RequestBody UpdatePreferenceRequest request
    ) {
        if (!request.isEnabled() && NotificationEventType.isNonOptional(request.getEventType())) {
            throw new IllegalArgumentException(
                    "Security-critical notifications (" + request.getEventType() + ") cannot be disabled for user protection."
            );
        }

        NotificationPreferenceId id = NotificationPreferenceId.builder()
                .userId(userId)
                .eventType(request.getEventType())
                .channel(request.getChannel())
                .build();

        NotificationPreference pref = preferenceRepository.findById(id)
                .orElseGet(() -> NotificationPreference.builder()
                        .id(id)
                        .createdAt(Instant.now())
                        .build());

        pref.setEnabled(request.isEnabled());
        pref.setUpdatedAt(Instant.now());
        NotificationPreference saved = preferenceRepository.save(pref);

        log.info("Updated notification preference: userId={}, eventType={}, channel={}, enabled={}",
                userId, request.getEventType(), request.getChannel(), request.isEnabled());
        return ResponseEntity.ok(saved);
    }

    @Data
    public static class UpdatePreferenceRequest {
        @NotBlank(message = "eventType is required")
        private String eventType;

        @NotNull(message = "channel is required")
        private NotificationChannel channel;

        private boolean enabled;
    }
}
