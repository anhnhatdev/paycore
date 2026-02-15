package com.paycore.accountservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    /**
     * SHA-256 hash of the raw JWT string.
     * Raw token is never stored — DB compromise does not expose usable tokens.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    /**
     * Optional device fingerprint / User-Agent for multi-device session management.
     */
    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    // --- Domain methods ---

    public boolean isValid() {
        return !revoked && expiresAt.isAfter(LocalDateTime.now());
    }

    public void revoke() {
        this.revoked = true;
    }
}
