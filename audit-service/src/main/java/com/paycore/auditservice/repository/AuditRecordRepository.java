package com.paycore.auditservice.repository;

import com.paycore.auditservice.domain.entity.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID>, JpaSpecificationExecutor<AuditRecord> {

    @Query("SELECT MAX(a.sequenceNumber) FROM AuditRecord a")
    Optional<Long> findMaxSequenceNumber();

    Optional<AuditRecord> findTopByOrderBySequenceNumberDesc();

    List<AuditRecord> findBySequenceNumberBetweenOrderBySequenceNumberAsc(Long fromSeq, Long toSeq);

    Optional<AuditRecord> findByEventId(UUID eventId);

    List<AuditRecord> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId);

    Page<AuditRecord> findAllByOrderByRecordedAtDesc(Pageable pageable);

    @Query("SELECT a FROM AuditRecord a WHERE a.recordedAt < :beforeDate ORDER BY a.sequenceNumber ASC")
    List<AuditRecord> findRecordsOlderThan(@org.springframework.data.repository.query.Param("beforeDate") Instant beforeDate, Pageable pageable);
}
