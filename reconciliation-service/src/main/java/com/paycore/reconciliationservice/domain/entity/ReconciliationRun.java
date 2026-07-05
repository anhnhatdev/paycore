package com.paycore.reconciliationservice.domain.entity;

import com.paycore.reconciliationservice.domain.enums.ReconciliationRunStatus;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", length = 30, nullable = false)
    private ReconciliationRunType runType;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private ReconciliationRunStatus status = ReconciliationRunStatus.RUNNING;

    @Column(name = "total_checked")
    @Builder.Default
    private int totalChecked = 0;

    @Column(name = "total_discrepancies")
    @Builder.Default
    private int totalDiscrepancies = 0;

    @Builder.Default
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
