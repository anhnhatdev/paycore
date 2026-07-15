package com.paycore.reconciliationservice.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", length = 20, nullable = false)
    private String provider;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "raw_file_reference", columnDefinition = "text", nullable = false)
    private String rawFileReference;

    @Column(name = "row_count")
    private Integer rowCount;

    @Builder.Default
    @Column(name = "downloaded_at", nullable = false, updatable = false)
    private Instant downloadedAt = Instant.now();
}
