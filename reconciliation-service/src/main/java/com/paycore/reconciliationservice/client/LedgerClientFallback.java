package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.AccountReconciliationDto;
import com.paycore.reconciliationservice.dto.GlobalLedgerTotalsDto;
import com.paycore.reconciliationservice.dto.LedgerEntryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class LedgerClientFallback implements LedgerClient {

    @Override
    public AccountReconciliationDto reconcileAccount(UUID accountId) {
        log.warn("LedgerClient fallback triggered for reconcileAccount: {}", accountId);
        return AccountReconciliationDto.builder()
                .accountId(accountId)
                .matched(true)
                .calculatedBalance(BigDecimal.ZERO)
                .storedBalance(BigDecimal.ZERO)
                .discrepancy(BigDecimal.ZERO)
                .build();
    }

    @Override
    public GlobalLedgerTotalsDto getGlobalTotals(Instant periodStart, Instant periodEnd) {
        log.warn("LedgerClient fallback triggered for getGlobalTotals");
        return GlobalLedgerTotalsDto.builder()
                .totalDebit(BigDecimal.ZERO)
                .totalCredit(BigDecimal.ZERO)
                .difference(BigDecimal.ZERO)
                .balanced(true)
                .build();
    }

    @Override
    public List<LedgerEntryDto> getEntriesByTransactionId(UUID transactionId) {
        log.warn("LedgerClient fallback triggered for getEntriesByTransactionId: {}", transactionId);
        return Collections.emptyList();
    }

    @Override
    public List<UUID> getActiveAccountIds(Instant periodStart, Instant periodEnd) {
        log.warn("LedgerClient fallback triggered for getActiveAccountIds");
        return Collections.emptyList();
    }

    @Override
    public List<UUID> getRecentTransactionIds(Instant periodStart, Instant periodEnd) {
        log.warn("LedgerClient fallback triggered for getRecentTransactionIds");
        return Collections.emptyList();
    }
}
