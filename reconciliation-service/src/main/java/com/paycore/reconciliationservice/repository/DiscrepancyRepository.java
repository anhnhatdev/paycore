package com.paycore.reconciliationservice.repository;

import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DiscrepancyRepository extends JpaRepository<Discrepancy, UUID>, JpaSpecificationExecutor<Discrepancy> {

    List<Discrepancy> findByReconciliationRunId(UUID reconciliationRunId);

    List<Discrepancy> findByStatus(DiscrepancyStatus status);

    List<Discrepancy> findByStatusAndSeverity(DiscrepancyStatus status, DiscrepancySeverity severity);

    Optional<Discrepancy> findFirstByDiscrepancyTypeAndEntityReferenceAndStatus(
            DiscrepancyType discrepancyType,
            String entityReference,
            DiscrepancyStatus status
    );

    Page<Discrepancy> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
