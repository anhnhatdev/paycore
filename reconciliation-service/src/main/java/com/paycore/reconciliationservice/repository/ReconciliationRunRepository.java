package com.paycore.reconciliationservice.repository;

import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunStatus;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    List<ReconciliationRun> findByRunTypeOrderByStartedAtDesc(ReconciliationRunType runType);

    List<ReconciliationRun> findByStatusOrderByStartedAtDesc(ReconciliationRunStatus status);

    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
