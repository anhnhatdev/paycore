package com.paycore.reconciliationservice.domain.entity;

import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discrepancies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Discrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reconciliation_run_id", nullable = false)
    private UUID reconciliationRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", length = 50, nullable = false)
    private DiscrepancyType discrepancyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10, nullable = false)
    private DiscrepancySeverity severity;

    @Column(name = "entity_reference", length = 255, nullable = false)
    private String entityReference;

    @Column(name = "expected_value", columnDefinition = "text")
    private String expectedValue;

    @Column(name = "actual_value", columnDefinition = "text")
    private String actualValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private DiscrepancyStatus status = DiscrepancyStatus.OPEN;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
