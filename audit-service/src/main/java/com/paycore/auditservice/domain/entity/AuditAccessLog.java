package com.paycore.auditservice.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_access_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "accessed_by", length = 255, nullable = false, updatable = false)
    private String accessedBy;

    @Column(name = "query_params", columnDefinition = "text", nullable = false, updatable = false)
    private String queryParams;

    @Column(name = "result_count", updatable = false)
    private Integer resultCount;

    @Builder.Default
    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt = Instant.now();
}
