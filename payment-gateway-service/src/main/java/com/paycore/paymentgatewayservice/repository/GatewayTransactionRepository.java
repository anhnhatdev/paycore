package com.paycore.paymentgatewayservice.repository;

import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayTransactionRepository extends JpaRepository<GatewayTransaction, UUID> {

    Optional<GatewayTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<GatewayTransaction> findByInternalTransactionId(UUID internalTransactionId);

    Optional<GatewayTransaction> findByProviderAndProviderTransactionRef(PaymentProvider provider, String providerTransactionRef);

    @Query("SELECT t FROM GatewayTransaction t WHERE t.status = :status AND t.updatedAt < :threshold")
    List<GatewayTransaction> findPendingTransactionsOlderThan(
            @Param("status") GatewayTransactionStatus status,
            @Param("threshold") Instant threshold
    );

    @Query("SELECT t FROM GatewayTransaction t WHERE t.status = 'PENDING_PROVIDER' AND t.expiresAt IS NOT NULL AND t.expiresAt < :now")
    List<GatewayTransaction> findExpiredPendingTransactions(@Param("now") Instant now);
}
