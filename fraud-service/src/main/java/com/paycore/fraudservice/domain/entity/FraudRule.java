package com.paycore.fraudservice.domain.entity;

import com.paycore.fraudservice.domain.enums.KycStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_code", unique = true, nullable = false, length = 50)
    private String ruleCode;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to_kyc_status", length = 20)
    private KycStatus appliesToKycStatus;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }
}
