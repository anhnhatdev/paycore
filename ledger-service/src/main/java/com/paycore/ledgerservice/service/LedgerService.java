package com.paycore.ledgerservice.service;

import com.paycore.ledgerservice.dto.BalanceResponse;
import com.paycore.ledgerservice.dto.CreateLedgerEntryRequest;
import com.paycore.ledgerservice.dto.CreateLedgerEntryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface LedgerService {

    CreateLedgerEntryResponse processDoubleEntry(CreateLedgerEntryRequest request);

    BalanceResponse getBalance(UUID accountId);

    Page<com.paycore.ledgerservice.domain.entity.LedgerEntry> getEntries(UUID accountId, Instant from, Instant to, Pageable pageable);
}
