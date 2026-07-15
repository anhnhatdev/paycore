package com.paycore.reconciliationservice.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.reconciliationservice.client.LedgerClient;
import com.paycore.reconciliationservice.client.TransactionClient;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.dto.LedgerEntryDto;
import com.paycore.reconciliationservice.dto.TransactionSummaryDto;
import com.paycore.reconciliationservice.service.DiscrepancyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrossServiceRunner implements ReconciliationRunner {

    private final TransactionClient transactionClient;
    private final LedgerClient ledgerClient;
    private final DiscrepancyService discrepancyService;
    private final ObjectMapper objectMapper;

    @Override
    public ReconciliationRunType getSupportedType() {
        return ReconciliationRunType.CROSS_SERVICE;
    }

    @Override
    public int runReconciliation(ReconciliationRun run) {
        log.info("Starting CROSS_SERVICE reconciliation run: id={}, period=[{} to {}]",
                run.getId(), run.getPeriodStart(), run.getPeriodEnd());

        int checkedCount = 0;
        int discrepancyCount = 0;

        // 1. Forward check: Transaction COMPLETED -> must have matching Ledger Entries
        List<TransactionSummaryDto> completedTxs = transactionClient.getCompletedTransactions(
                run.getPeriodStart(), run.getPeriodEnd()
        );

        for (TransactionSummaryDto tx : completedTxs) {
            checkedCount++;
            try {
                List<LedgerEntryDto> entries = ledgerClient.getEntriesByTransactionId(tx.getId());
                if (entries == null || entries.isEmpty()) {
                    discrepancyCount++;
                    String expectedJson = objectMapper.writeValueAsString(Map.of(
                            "transactionId", tx.getId().toString(),
                            "status", tx.getStatus(),
                            "expectedLedgerEntries", ">= 2 (DEBIT + CREDIT)"
                    ));
                    String actualJson = objectMapper.writeValueAsString(Map.of(
                            "ledgerEntriesCount", 0,
                            "error", "Transaction marked COMPLETED but no corresponding ledger entries exist"
                    ));

                    discrepancyService.recordDiscrepancy(
                            run.getId(),
                            DiscrepancyType.MISSING_LEDGER_ENTRY,
                            DiscrepancySeverity.HIGH,
                            tx.getId().toString(),
                            expectedJson,
                            actualJson
                    );
                }
            } catch (Exception e) {
                log.error("Error checking ledger entries for transaction {}: {}", tx.getId(), e.getMessage());
            }
        }

        // 2. Reverse check: Ledger Entries -> must map to a valid Transaction
        try {
            List<UUID> ledgerTxIds = ledgerClient.getRecentTransactionIds(run.getPeriodStart(), run.getPeriodEnd());
            for (UUID txId : ledgerTxIds) {
                checkedCount++;
                TransactionSummaryDto tx = transactionClient.getTransactionById(txId);
                if (tx == null) {
                    discrepancyCount++;
                    String expectedJson = objectMapper.writeValueAsString(Map.of(
                            "transactionId", txId.toString(),
                            "expected", "Valid Transaction entity in transaction-service"
                    ));
                    String actualJson = objectMapper.writeValueAsString(Map.of(
                            "error", "Ledger entry exists for transaction_id with no matching transaction record"
                    ));

                    discrepancyService.recordDiscrepancy(
                            run.getId(),
                            DiscrepancyType.ORPHAN_LEDGER_ENTRY,
                            DiscrepancySeverity.HIGH,
                            txId.toString(),
                            expectedJson,
                            actualJson
                    );
                }
            }
        } catch (Exception e) {
            log.error("Error during reverse cross-service check: {}", e.getMessage());
        }

        run.setTotalChecked(checkedCount);
        run.setTotalDiscrepancies(discrepancyCount);
        log.info("Finished CROSS_SERVICE: totalChecked={}, discrepancies={}", checkedCount, discrepancyCount);
        return discrepancyCount;
    }
}
