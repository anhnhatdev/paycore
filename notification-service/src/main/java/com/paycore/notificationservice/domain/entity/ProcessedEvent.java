package com.paycore.notificationservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "source_service", length = 50, nullable = false)
    private String sourceService;

    @Builder.Default
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt = Instant.now();
}
