package com.paycore.fraudservice.domain.entity;

import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.domain.enums.ReviewDecision;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_check_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCheckLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FraudDecision decision;

    @Column(name = "reason_codes", nullable = false, length = 1000)
    private String reasonCodes;

    @Column(name = "rules_evaluated", columnDefinition = "TEXT")
    private String rulesEvaluated;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_decision", length = 10)
    private ReviewDecision reviewDecision;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
