package com.paycore.ledgerservice.service;

import com.paycore.ledgerservice.domain.entity.Balance;
import com.paycore.ledgerservice.domain.entity.EntryType;
import com.paycore.ledgerservice.dto.ReconciliationResponse;
import com.paycore.ledgerservice.exception.AccountNotFoundException;
import com.paycore.ledgerservice.repository.BalanceRepository;
import com.paycore.ledgerservice.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final BalanceRepository balanceRepository;

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcileAccount(UUID accountId) {
        Balance balance = balanceRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account balance record not found: " + accountId));

        BigDecimal totalCredits = ledgerEntryRepository.sumAmountByAccountIdAndEntryType(accountId, EntryType.CREDIT);
        BigDecimal totalDebits = ledgerEntryRepository.sumAmountByAccountIdAndEntryType(accountId, EntryType.DEBIT);

        BigDecimal calculatedBalance = totalCredits.subtract(totalDebits);
        boolean isBalanced = calculatedBalance.compareTo(balance.getAvailableBalance()) == 0;

        if (!isBalanced) {
            log.error("AUDIT_ALERT | Account {} balance mismatch! Stored={}, Calculated={} (Credits={}, Debits={})",
                    accountId, balance.getAvailableBalance(), calculatedBalance, totalCredits, totalDebits);
        } else {
            log.info("RECONCILE_OK | Account {} is fully balanced. Stored={}, Calculated={}",
                    accountId, balance.getAvailableBalance(), calculatedBalance);
        }

        return ReconciliationResponse.builder()
                .accountId(accountId)
                .balanceStored(balance.getAvailableBalance())
                .balanceCalculated(calculatedBalance)
                .isBalanced(isBalanced)
                .totalDebits(totalDebits)
                .totalCredits(totalCredits)
                .reconciliationTimestamp(Instant.now())
                .build();
    }
}
