package com.paycore.auditservice.domain.entity;

import com.paycore.auditservice.domain.enums.ActorType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private Long sequenceNumber;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "source_service", length = 50, nullable = false, updatable = false)
    private String sourceService;

    @Column(name = "event_type", length = 100, nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 20, nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", length = 255, updatable = false)
    private String actorId;

    @Column(name = "entity_type", length = 50, updatable = false)
    private String entityType;

    @Column(name = "entity_id", length = 255, updatable = false)
    private String entityId;

    @Column(name = "payload", columnDefinition = "text", nullable = false, updatable = false)
    private String payload;

    @Column(name = "record_hash", length = 64, nullable = false, updatable = false)
    private String recordHash;

    @Column(name = "prev_hash", length = 64, nullable = false, updatable = false)
    private String prevHash;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Builder.Default
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();
}
