package com.paycore.ledgerservice.repository;

import com.paycore.ledgerservice.domain.entity.EntryType;
import com.paycore.ledgerservice.domain.entity.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    Page<LedgerEntry> findByAccountIdAndCreatedAtBetween(
            UUID accountId, Instant from, Instant to, Pageable pageable
    );

    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId AND e.entryType = :entryType")
    BigDecimal sumAmountByAccountIdAndEntryType(
            @Param("accountId") UUID accountId,
            @Param("entryType") EntryType entryType
    );
}
