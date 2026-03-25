package com.paycore.ledgerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.ledgerservice.domain.entity.Balance;
import com.paycore.ledgerservice.domain.entity.EntryType;
import com.paycore.ledgerservice.domain.entity.IdempotencyStatus;
import com.paycore.ledgerservice.domain.entity.LedgerEntry;
import com.paycore.ledgerservice.domain.entity.OutboxEvent;
import com.paycore.ledgerservice.dto.ReverseLedgerEntryRequest;
import com.paycore.ledgerservice.dto.ReverseLedgerEntryResponse;
import com.paycore.ledgerservice.exception.LedgerException;
import com.paycore.ledgerservice.idempotency.IdempotencyManager;
import com.paycore.ledgerservice.idempotency.IdempotencySnapshot;
import com.paycore.ledgerservice.repository.BalanceRepository;
import com.paycore.ledgerservice.repository.LedgerEntryRepository;
import com.paycore.ledgerservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReversalService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final BalanceRepository balanceRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyManager idempotencyManager;
    private final ObjectMapper objectMapper;

    public ReverseLedgerEntryResponse processReversal(ReverseLedgerEntryRequest request) {
        // Phase 0: Idempotency check
        Optional<IdempotencySnapshot> snapshotOpt = idempotencyManager.startOrCheckIdempotency(
                request.getIdempotencyKey(), request
        );

        if (snapshotOpt.isPresent()) {
            IdempotencySnapshot snapshot = snapshotOpt.get();
            try {
                if (snapshot.getStatus() == IdempotencyStatus.COMPLETED) {
                    return objectMapper.readValue(snapshot.getResponseJson(), ReverseLedgerEntryResponse.class);
                }
            } catch (Exception e) {
                log.error("Failed to deserialize reversal idempotency snapshot", e);
            }
        }

        return executeReversal(request);
    }

    @Transactional
    public ReverseLedgerEntryResponse executeReversal(ReverseLedgerEntryRequest request) {
        List<LedgerEntry> originalEntries = ledgerEntryRepository.findByTransactionId(request.getOriginalTransactionId());
        if (originalEntries.isEmpty() || originalEntries.size() < 2) {
            throw new LedgerException("Original transaction entries not found for transactionId: " + request.getOriginalTransactionId());
        }

        LedgerEntry origDebit = originalEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT && e.getReversalOfEntryId() == null)
                .findFirst()
                .orElseThrow(() -> new LedgerException("Original DEBIT entry not found or already reversed"));

        LedgerEntry origCredit = originalEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT && e.getReversalOfEntryId() == null)
                .findFirst()
                .orElseThrow(() -> new LedgerException("Original CREDIT entry not found or already reversed"));

        UUID debitAccId = origDebit.getAccountId();
        UUID creditAccId = origCredit.getAccountId();

        // Lock balances in deterministic order
        UUID firstId = debitAccId.toString().compareTo(creditAccId.toString()) < 0 ? debitAccId : creditAccId;
        UUID secondId = firstId.equals(debitAccId) ? creditAccId : debitAccId;

        Balance firstBal = balanceRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new LedgerException("Balance not found: " + firstId));
        Balance secondBal = balanceRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new LedgerException("Balance not found: " + secondId));

        Balance debitBal = firstId.equals(debitAccId) ? firstBal : secondBal;
        Balance creditBal = firstId.equals(creditAccId) ? firstBal : secondBal;

        // Reverse balances: refund debit account, clawback credit account
        debitBal.setAvailableBalance(debitBal.getAvailableBalance().add(origDebit.getAmount()));
        creditBal.setAvailableBalance(creditBal.getAvailableBalance().subtract(origCredit.getAmount()));

        balanceRepository.save(debitBal);
        balanceRepository.save(creditBal);

        UUID reversalTxId = UUID.randomUUID();

        // Append reversal entries
        LedgerEntry revCredit = LedgerEntry.builder()
                .transactionId(reversalTxId)
                .accountId(debitAccId)
                .entryType(EntryType.CREDIT)
                .amount(origDebit.getAmount())
                .currency(origDebit.getCurrency())
                .balanceAfter(debitBal.getAvailableBalance())
                .reversalOfEntryId(origDebit.getId())
                .build();
        revCredit = ledgerEntryRepository.save(revCredit);

        LedgerEntry revDebit = LedgerEntry.builder()
                .transactionId(reversalTxId)
                .accountId(creditAccId)
                .entryType(EntryType.DEBIT)
                .amount(origCredit.getAmount())
                .currency(origCredit.getCurrency())
                .balanceAfter(creditBal.getAvailableBalance())
                .reversalOfEntryId(origCredit.getId())
                .build();
        revDebit = ledgerEntryRepository.save(revDebit);

        // Transactional Outbox event
        try {
            String payloadJson = objectMapper.writeValueAsString(new ReversalPayload(
                    request.getOriginalTransactionId(),
                    reversalTxId,
                    request.getReason(),
                    revCredit.getId(),
                    revDebit.getId()
            ));
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(request.getOriginalTransactionId())
                    .eventType("LedgerEntryReversed")
                    .payload(payloadJson)
                    .published(false)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed to serialize reversal outbox payload", e);
        }

        ReverseLedgerEntryResponse response = ReverseLedgerEntryResponse.builder()
                .originalTransactionId(request.getOriginalTransactionId())
                .reversalDebitEntryId(revDebit.getId())
                .reversalCreditEntryId(revCredit.getId())
                .status("REVERSED")
                .build();

        idempotencyManager.completeIdempotency(request.getIdempotencyKey(), response);
        return response;
    }

    public record ReversalPayload(
            UUID originalTransactionId,
            UUID reversalTransactionId,
            String reason,
            UUID refundEntryId,
            UUID clawbackEntryId
    ) {}
}
