package com.paycore.reconciliationservice.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.reconciliationservice.client.LedgerClient;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.dto.AccountReconciliationDto;
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
public class InternalPerAccountRunner implements ReconciliationRunner {

    private final LedgerClient ledgerClient;
    private final DiscrepancyService discrepancyService;
    private final ObjectMapper objectMapper;

    @Override
    public ReconciliationRunType getSupportedType() {
        return ReconciliationRunType.INTERNAL_PER_ACCOUNT;
    }

    @Override
    public int runReconciliation(ReconciliationRun run) {
        log.info("Starting INTERNAL_PER_ACCOUNT reconciliation run: id={}, period=[{} to {}]",
                run.getId(), run.getPeriodStart(), run.getPeriodEnd());

        List<UUID> activeAccountIds = ledgerClient.getActiveAccountIds(run.getPeriodStart(), run.getPeriodEnd());
        int checkedCount = 0;
        int discrepancyCount = 0;

        for (UUID accountId : activeAccountIds) {
            checkedCount++;
            try {
                AccountReconciliationDto result = ledgerClient.reconcileAccount(accountId);
                if (!result.isMatched()) {
                    discrepancyCount++;
                    String expectedJson = objectMapper.writeValueAsString(Map.of(
                            "calculatedBalance", result.getCalculatedBalance() != null ? result.getCalculatedBalance().toString() : "0"
                    ));
                    String actualJson = objectMapper.writeValueAsString(Map.of(
                            "storedBalance", result.getStoredBalance() != null ? result.getStoredBalance().toString() : "0",
                            "discrepancy", result.getDiscrepancy() != null ? result.getDiscrepancy().toString() : "0"
                    ));

                    discrepancyService.recordDiscrepancy(
                            run.getId(),
                            DiscrepancyType.BALANCE_MISMATCH,
                            DiscrepancySeverity.MEDIUM,
                            accountId.toString(),
                            expectedJson,
                            actualJson
                    );
                }
            } catch (Exception e) {
                log.error("Error reconciling account {}: {}", accountId, e.getMessage());
            }
        }

        run.setTotalChecked(checkedCount);
        run.setTotalDiscrepancies(discrepancyCount);
        log.info("Finished INTERNAL_PER_ACCOUNT: totalChecked={}, discrepancies={}", checkedCount, discrepancyCount);
        return discrepancyCount;
    }
}
