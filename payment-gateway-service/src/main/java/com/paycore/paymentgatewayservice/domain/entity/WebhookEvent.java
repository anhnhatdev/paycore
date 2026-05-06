package com.paycore.paymentgatewayservice.domain.entity;

import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import com.paycore.paymentgatewayservice.domain.enums.WebhookProcessingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_event_id")
    private String providerEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    @Builder.Default
    private WebhookProcessingStatus processingStatus = WebhookProcessingStatus.RECEIVED;

    @Column(name = "gateway_transaction_id")
    private UUID gatewayTransactionId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
        if (this.processingStatus == null) {
            this.processingStatus = WebhookProcessingStatus.RECEIVED;
        }
    }
}
