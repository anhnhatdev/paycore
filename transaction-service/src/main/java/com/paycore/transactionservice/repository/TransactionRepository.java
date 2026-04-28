package com.paycore.transactionservice.repository;

import com.paycore.transactionservice.domain.entity.Transaction;
import com.paycore.transactionservice.domain.enums.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByClientIdempotencyKey(String clientIdempotencyKey);

    Page<Transaction> findByUserId(UUID userId, Pageable pageable);

    Page<Transaction> findByUserIdAndStatus(UUID userId, TransactionStatus status, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE (t.fromAccountId = :accountId OR t.toAccountId = :accountId)")
    Page<Transaction> findByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.status IN :statuses AND t.updatedAt < :threshold")
    List<Transaction> findStuckTransactions(
            @Param("statuses") List<TransactionStatus> statuses,
            @Param("threshold") Instant threshold
    );
}
