package com.paycore.reconciliationservice.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.reconciliationservice.client.PaymentGatewayClient;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.entity.SettlementReport;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.dto.GatewayTransactionDto;
import com.paycore.reconciliationservice.dto.SettlementRow;
import com.paycore.reconciliationservice.parser.SettlementReportParser;
import com.paycore.reconciliationservice.repository.SettlementReportRepository;
import com.paycore.reconciliationservice.service.DiscrepancyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalGatewayRunner implements ReconciliationRunner {

    private final PaymentGatewayClient gatewayClient;
    private final SettlementReportRepository settlementReportRepository;
    private final SettlementReportParser settlementReportParser;
    private final DiscrepancyService discrepancyService;
    private final ObjectMapper objectMapper;

    // Buffer for passing in-memory settlement rows (used by REST triggers and integration tests)
    private final List<SettlementRow> inMemorySettlementBuffer = Collections.synchronizedList(new ArrayList<>());

    public void setInMemorySettlementRows(List<SettlementRow> rows) {
        this.inMemorySettlementBuffer.clear();
        if (rows != null) {
            this.inMemorySettlementBuffer.addAll(rows);
        }
    }

    @Override
    public ReconciliationRunType getSupportedType() {
        return ReconciliationRunType.EXTERNAL_GATEWAY;
    }

    @Override
    public int runReconciliation(ReconciliationRun run) {
        log.info("Starting EXTERNAL_GATEWAY reconciliation run: id={}, period=[{} to {}]",
                run.getId(), run.getPeriodStart(), run.getPeriodEnd());

        List<SettlementRow> settlementRows = new ArrayList<>(inMemorySettlementBuffer);

        // If buffer is empty, attempt to load latest settlement reports in the period
        if (settlementRows.isEmpty()) {
            LocalDate startDate = run.getPeriodStart().atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate endDate = run.getPeriodEnd().atZone(ZoneOffset.UTC).toLocalDate();

            List<SettlementReport> reports = settlementReportRepository.findAll().stream()
                    .filter(r -> !r.getReportDate().isBefore(startDate) && !r.getReportDate().isAfter(endDate))
                    .toList();

            for (SettlementReport report : reports) {
                settlementRows.addAll(settlementReportParser.parseCsvReport(report.getRawFileReference()));
            }
        }

        int checkedCount = 0;
        int discrepancyCount = 0;
        Set<String> processedReportRefs = new HashSet<>();

        // 1. Forward match: Settlement Report Rows -> Gateway Transactions
        for (SettlementRow row : settlementRows) {
            checkedCount++;
            String ref = row.getProviderTransactionRef();
            processedReportRefs.add(ref);

            try {
                GatewayTransactionDto internalTx = gatewayClient.getTransactionByProviderRef(ref);

                if (internalTx == null) {
                    discrepancyCount++;
                    String expectedJson = objectMapper.writeValueAsString(Map.of(
                            "providerTransactionRef", ref,
                            "amount", row.getAmount().toString(),
                            "currency", row.getCurrency(),
                            "status", row.getStatus()
                    ));
                    String actualJson = objectMapper.writeValueAsString(Map.of(
                            "error", "Settlement report contains transaction that does not exist in internal gateway_transactions"
                    ));

                    discrepancyService.recordDiscrepancy(
                            run.getId(),
                            DiscrepancyType.GATEWAY_MISSING_INTERNAL_RECORD,
                            DiscrepancySeverity.HIGH,
                            ref,
                            expectedJson,
                            actualJson
                    );
                } else {
                    // Check Amount match
                    if (row.getAmount().compareTo(internalTx.getAmount()) != 0) {
                        discrepancyCount++;
                        String expectedJson = objectMapper.writeValueAsString(Map.of(
                                "settlementAmount", row.getAmount().toString(),
                                "currency", row.getCurrency()
                        ));
                        String actualJson = objectMapper.writeValueAsString(Map.of(
                                "internalAmount", internalTx.getAmount().toString(),
                                "internalId", internalTx.getId().toString()
                        ));

                        discrepancyService.recordDiscrepancy(
                                run.getId(),
                                DiscrepancyType.GATEWAY_AMOUNT_MISMATCH,
                                DiscrepancySeverity.CRITICAL,
                                ref,
                                expectedJson,
                                actualJson
                        );
                    }
                }
            } catch (Exception e) {
                log.error("Error checking settlement row ref {}: {}", ref, e.getMessage());
            }
        }

        // 2. Reverse match: Internal Succeeded Transactions in period -> Settlement Report
        try {
            List<GatewayTransactionDto> internalTxs = gatewayClient.getGatewayTransactions(
                    run.getPeriodStart(), run.getPeriodEnd()
            );

            for (GatewayTransactionDto tx : internalTxs) {
                if ("SUCCEEDED".equalsIgnoreCase(tx.getStatus()) && tx.getProviderTransactionRef() != null) {
                    checkedCount++;
                    if (!processedReportRefs.contains(tx.getProviderTransactionRef())) {
                        discrepancyCount++;
                        String expectedJson = objectMapper.writeValueAsString(Map.of(
                                "internalStatus", "SUCCEEDED",
                                "providerTransactionRef", tx.getProviderTransactionRef(),
                                "amount", tx.getAmount().toString()
                        ));
                        String actualJson = objectMapper.writeValueAsString(Map.of(
                                "error", "Internal transaction marked SUCCEEDED is missing from official settlement report"
                        ));

                        discrepancyService.recordDiscrepancy(
                                run.getId(),
                                DiscrepancyType.GATEWAY_MISSING_INTERNAL_RECORD,
                                DiscrepancySeverity.HIGH,
                                tx.getProviderTransactionRef(),
                                expectedJson,
                                actualJson
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during reverse gateway reconciliation check: {}", e.getMessage());
        }

        run.setTotalChecked(checkedCount);
        run.setTotalDiscrepancies(discrepancyCount);
        log.info("Finished EXTERNAL_GATEWAY: totalChecked={}, discrepancies={}", checkedCount, discrepancyCount);
        return discrepancyCount;
    }
}
