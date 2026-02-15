package com.paycore.accountservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    /**
     * References users.id — no FK in JPA to enforce Database-per-Service boundary clarity.
     * The FK exists at DB level via Flyway migration.
     */
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "account_number", unique = true, nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * Optimistic locking — prevents race condition on status update (e.g. concurrent freeze requests).
     */
    @jakarta.persistence.Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum AccountStatus {
        ACTIVE, FROZEN, CLOSED
    }

    // --- Domain methods ---

    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    public void freeze() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot freeze a closed account");
        }
        this.status = AccountStatus.FROZEN;
    }

    public void unfreeze() {
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot unfreeze a closed account");
        }
        this.status = AccountStatus.ACTIVE;
    }
}
