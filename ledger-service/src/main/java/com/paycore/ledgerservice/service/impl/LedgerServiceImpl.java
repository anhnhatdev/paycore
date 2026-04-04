package com.paycore.ledgerservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.ledgerservice.domain.entity.*;
import com.paycore.ledgerservice.dto.*;
import com.paycore.ledgerservice.exception.AccountNotFoundException;
import com.paycore.ledgerservice.exception.CurrencyMismatchException;
import com.paycore.ledgerservice.exception.InsufficientBalanceException;
import com.paycore.ledgerservice.idempotency.IdempotencyManager;
import com.paycore.ledgerservice.idempotency.IdempotencySnapshot;
import com.paycore.ledgerservice.repository.BalanceRepository;
import com.paycore.ledgerservice.repository.LedgerEntryRepository;
import com.paycore.ledgerservice.repository.OutboxEventRepository;
import com.paycore.ledgerservice.repository.SystemAccountRepository;
import com.paycore.ledgerservice.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerServiceImpl implements LedgerService {

    private final BalanceRepository balanceRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SystemAccountRepository systemAccountRepository;
    private final IdempotencyManager idempotencyManager;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CreateLedgerEntryResponse processDoubleEntry(CreateLedgerEntryRequest request) {
        // 1. Pre-validation
        if (request.getDebitAccountId().equals(request.getCreditAccountId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive");
        }

        // 2. Phase 0: Idempotency check / lock
        Optional<IdempotencySnapshot> snapshotOpt = idempotencyManager.startOrCheckIdempotency(
                request.getIdempotencyKey(), request
        );

        if (snapshotOpt.isPresent()) {
            IdempotencySnapshot snapshot = snapshotOpt.get();
            try {
                if (snapshot.getStatus() == IdempotencyStatus.COMPLETED) {
                    return objectMapper.readValue(snapshot.getResponseJson(), CreateLedgerEntryResponse.class);
                } else if (snapshot.getStatus() == IdempotencyStatus.FAILED) {
                    FailedLedgerEntryResponse failed = objectMapper.readValue(snapshot.getResponseJson(), FailedLedgerEntryResponse.class);
                    throw new InsufficientBalanceException(failed.getReason(), failed.getAvailableBalance());
                }
            } catch (InsufficientBalanceException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to deserialize idempotency snapshot: {}", snapshot.getResponseJson(), e);
            }
        }

        // 3. Phase 1: Main Double-Entry Transaction
        return executeDoubleEntry(request);
    }

    @Transactional
    public CreateLedgerEntryResponse executeDoubleEntry(CreateLedgerEntryRequest request) {
        UUID debitId = request.getDebitAccountId();
        UUID creditId = request.getCreditAccountId();

        // 1. Deadlock Prevention: Deterministic ascending locking order
        UUID firstId = debitId.toString().compareTo(creditId.toString()) < 0 ? debitId : creditId;
        UUID secondId = firstId.equals(debitId) ? creditId : debitId;

        Balance firstBalance = getOrInitBalanceForUpdate(firstId, request.getCurrency());
        Balance secondBalance = getOrInitBalanceForUpdate(secondId, request.getCurrency());

        Balance debitBalance = firstId.equals(debitId) ? firstBalance : secondBalance;
        Balance creditBalance = firstId.equals(creditId) ? firstBalance : secondBalance;

        // 2. Currency Validation
        if (!debitBalance.getCurrency().equalsIgnoreCase(creditBalance.getCurrency()) ||
                !debitBalance.getCurrency().equalsIgnoreCase(request.getCurrency())) {
            throw new CurrencyMismatchException(String.format(
                    "Currency mismatch: debit=%s, credit=%s, request=%s",
                    debitBalance.getCurrency(), creditBalance.getCurrency(), request.getCurrency()
            ));
        }

        // 3. Balance Sufficiency Check (Bypassed if debit account is a System Account e.g. SUSPENSE)
        boolean isDebitSystemAccount = systemAccountRepository.existsById(debitId);
        if (!isDebitSystemAccount) {
            if (debitBalance.getAvailableBalance().compareTo(request.getAmount()) < 0) {
                log.warn("Insufficient balance for account {}: available={}, required={}",
                        debitId, debitBalance.getAvailableBalance(), request.getAmount());

                FailedLedgerEntryResponse failedResponse = FailedLedgerEntryResponse.builder()
                        .status("FAILED")
                        .reason("INSUFFICIENT_BALANCE")
                        .availableBalance(debitBalance.getAvailableBalance())
                        .build();

                // Record failure in isolated transaction
                idempotencyManager.failIdempotency(request.getIdempotencyKey(), failedResponse);

                throw new InsufficientBalanceException("Insufficient balance", debitBalance.getAvailableBalance());
            }
        }

        // 4. Update Balances
        debitBalance.setAvailableBalance(debitBalance.getAvailableBalance().subtract(request.getAmount()));
        creditBalance.setAvailableBalance(creditBalance.getAvailableBalance().add(request.getAmount()));

        balanceRepository.save(debitBalance);
        balanceRepository.save(creditBalance);

        // 5. Append Immutable Ledger Entries
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transactionId(request.getTransactionId())
                .accountId(debitId)
                .entryType(EntryType.DEBIT)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .balanceAfter(debitBalance.getAvailableBalance())
                .build();
        debitEntry = ledgerEntryRepository.save(debitEntry);

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transactionId(request.getTransactionId())
                .accountId(creditId)
                .entryType(EntryType.CREDIT)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .balanceAfter(creditBalance.getAvailableBalance())
                .build();
        creditEntry = ledgerEntryRepository.save(creditEntry);

        // 6. Append Transactional Outbox Event
        try {
            String payloadJson = objectMapper.writeValueAsString(new LedgerCreatedPayload(
                    request.getTransactionId(),
                    debitEntry.getId(),
                    creditEntry.getId(),
                    debitId,
                    creditId,
                    request.getAmount(),
                    request.getCurrency(),
                    debitBalance.getAvailableBalance(),
                    creditBalance.getAvailableBalance()
            ));

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(request.getTransactionId())
                    .eventType("LedgerEntryCreated")
                    .payload(payloadJson)
                    .published(false)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to write outbox event for transaction {}", request.getTransactionId(), e);
        }

        // 7. Assemble response & Complete Idempotency
        CreateLedgerEntryResponse response = CreateLedgerEntryResponse.builder()
                .debitEntryId(debitEntry.getId())
                .creditEntryId(creditEntry.getId())
                .debitBalanceAfter(debitBalance.getAvailableBalance())
                .creditBalanceAfter(creditBalance.getAvailableBalance())
                .status("COMPLETED")
                .build();

        idempotencyManager.completeIdempotency(request.getIdempotencyKey(), response);
        return response;
    }

    @Override
    public BalanceResponse getBalance(UUID accountId) {
        Balance balance = balanceRepository.findById(accountId)
                .orElseGet(() -> {
                    if (systemAccountRepository.existsById(accountId)) {
                        return initSystemBalance(accountId);
                    }
                    throw new AccountNotFoundException("Balance not found for account: " + accountId);
                });

        return BalanceResponse.builder()
                .accountId(balance.getAccountId())
                .currency(balance.getCurrency())
                .availableBalance(balance.getAvailableBalance())
                .pendingBalance(balance.getPendingBalance())
                .version(balance.getVersion())
                .updatedAt(balance.getUpdatedAt())
                .build();
    }

    @Override
    public Page<LedgerEntry> getEntries(UUID accountId, Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null) {
            return ledgerEntryRepository.findByAccountIdAndCreatedAtBetween(accountId, from, to, pageable);
        }
        return ledgerEntryRepository.findByAccountId(accountId, pageable);
    }

    private Balance getOrInitBalanceForUpdate(UUID accountId, String fallbackCurrency) {
        return balanceRepository.findByIdForUpdate(accountId)
                .orElseGet(() -> {
                    if (systemAccountRepository.existsById(accountId)) {
                        return initSystemBalance(accountId);
                    }
                    // Initialize empty balance for regular account if not exists
                    Balance newBalance = Balance.builder()
                            .accountId(accountId)
                            .currency(fallbackCurrency)
                            .availableBalance(BigDecimal.ZERO)
                            .pendingBalance(BigDecimal.ZERO)
                            .build();
                    return balanceRepository.save(newBalance);
                });
    }

    private Balance initSystemBalance(UUID accountId) {
        SystemAccount sys = systemAccountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("System account not found: " + accountId));
        Balance balance = Balance.builder()
                .accountId(accountId)
                .currency(sys.getCurrency())
                .availableBalance(BigDecimal.ZERO)
                .pendingBalance(BigDecimal.ZERO)
                .build();
        return balanceRepository.save(balance);
    }

    public record LedgerCreatedPayload(
            UUID transactionId,
            UUID debitEntryId,
            UUID creditEntryId,
            UUID debitAccountId,
            UUID creditAccountId,
            BigDecimal amount,
            String currency,
            BigDecimal debitBalanceAfter,
            BigDecimal creditBalanceAfter
    ) {}
}
