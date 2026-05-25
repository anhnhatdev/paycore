package com.paycore.fraudservice.repository;

import com.paycore.fraudservice.domain.entity.FraudCheckLog;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudCheckLogRepository extends JpaRepository<FraudCheckLog, UUID> {
    Optional<FraudCheckLog> findByTransactionId(UUID transactionId);
    List<FraudCheckLog> findByDecisionAndReviewDecisionIsNull(FraudDecision decision);
}
