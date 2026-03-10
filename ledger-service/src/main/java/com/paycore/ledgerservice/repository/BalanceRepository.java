package com.paycore.ledgerservice.repository;

import com.paycore.ledgerservice.domain.entity.Balance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BalanceRepository extends JpaRepository<Balance, UUID> {

    /**
     * Pessimistic Write Lock (SELECT ... FOR UPDATE)
     * Primary locking mechanism to serialize concurrent balance updates without optimistic retry overhead.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Balance b WHERE b.accountId = :accountId")
    Optional<Balance> findByIdForUpdate(@Param("accountId") UUID accountId);
}
