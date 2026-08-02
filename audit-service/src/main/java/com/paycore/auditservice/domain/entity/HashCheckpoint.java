package com.paycore.auditservice.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hash_checkpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HashCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "up_to_sequence_number", nullable = false, updatable = false)
    private Long upToSequenceNumber;

    @Column(name = "checkpoint_hash", length = 64, nullable = false, updatable = false)
    private String checkpointHash;

    @Column(name = "published_reference", columnDefinition = "text", updatable = false)
    private String publishedReference;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
