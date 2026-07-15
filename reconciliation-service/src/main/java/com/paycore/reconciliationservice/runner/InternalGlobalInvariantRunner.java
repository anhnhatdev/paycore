package com.paycore.reconciliationservice.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.reconciliationservice.client.LedgerClient;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.dto.GlobalLedgerTotalsDto;
import com.paycore.reconciliationservice.service.DiscrepancyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class InternalGlobalInvariantRunner implements ReconciliationRunner {

    private final LedgerClient ledgerClient;
    private final DiscrepancyService discrepancyService;
    private final ObjectMapper objectMapper;

    @Override
    public ReconciliationRunType getSupportedType() {
        return ReconciliationRunType.INTERNAL_GLOBAL_INVARIANT;
    }

    @Override
    public int runReconciliation(ReconciliationRun run) {
        log.info("Starting INTERNAL_GLOBAL_INVARIANT reconciliation run: id={}, period=[{} to {}]",
                run.getId(), run.getPeriodStart(), run.getPeriodEnd());

        int discrepancyCount = 0;
        int checkedCount = 1; // 1 global invariant check

        try {
            GlobalLedgerTotalsDto totals = ledgerClient.getGlobalTotals(run.getPeriodStart(), run.getPeriodEnd());

            BigDecimal totalDebit = totals.getTotalDebit() != null ? totals.getTotalDebit() : BigDecimal.ZERO;
            BigDecimal totalCredit = totals.getTotalCredit() != null ? totals.getTotalCredit() : BigDecimal.ZERO;
            BigDecimal difference = totals.getDifference() != null ? totals.getDifference() : totalDebit.subtract(totalCredit).abs();

            boolean balanced = totals.isBalanced() && difference.compareTo(BigDecimal.ZERO) == 0;

            if (!balanced) {
                discrepancyCount++;
                String expectedJson = objectMapper.writeValueAsString(Map.of(
                        "rule", "totalDebit == totalCredit",
                        "expectedDifference", "0.00"
                ));
                String actualJson = objectMapper.writeValueAsString(Map.of(
                        "totalDebit", totalDebit.toString(),
                        "totalCredit", totalCredit.toString(),
                        "difference", difference.toString()
                ));

                discrepancyService.recordDiscrepancy(
                        run.getId(),
                        DiscrepancyType.GLOBAL_INVARIANT_VIOLATION,
                        DiscrepancySeverity.CRITICAL,
                        "GLOBAL_DOUBLE_ENTRY_LEDGER",
                        expectedJson,
                        actualJson
                );
            }
        } catch (Exception e) {
            log.error("Failed to execute INTERNAL_GLOBAL_INVARIANT reconciliation: {}", e.getMessage(), e);
        }

        run.setTotalChecked(checkedCount);
        run.setTotalDiscrepancies(discrepancyCount);
        log.info("Finished INTERNAL_GLOBAL_INVARIANT: totalChecked={}, discrepancies={}", checkedCount, discrepancyCount);
        return discrepancyCount;
    }
}
