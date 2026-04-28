package com.paycore.transactionservice.repository;

import com.paycore.transactionservice.domain.entity.SagaLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SagaLogRepository extends JpaRepository<SagaLog, UUID> {

    List<SagaLog> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
