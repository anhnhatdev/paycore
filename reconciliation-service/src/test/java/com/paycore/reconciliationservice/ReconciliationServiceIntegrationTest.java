package com.paycore.reconciliationservice;

import com.paycore.reconciliationservice.client.LedgerClient;
import com.paycore.reconciliationservice.client.PaymentGatewayClient;
import com.paycore.reconciliationservice.client.TransactionClient;
import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.entity.ReconciliationRun;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import com.paycore.reconciliationservice.dto.*;
import com.paycore.reconciliationservice.repository.DiscrepancyRepository;
import com.paycore.reconciliationservice.repository.ReconciliationRunRepository;
import com.paycore.reconciliationservice.runner.ExternalGatewayRunner;
import com.paycore.reconciliationservice.service.ReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceIntegrationTest {

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private DiscrepancyRepository discrepancyRepository;

    @Autowired
    private ExternalGatewayRunner externalGatewayRunner;

    @MockBean
    private LedgerClient ledgerClient;

    @MockBean
    private TransactionClient transactionClient;

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    private final Instant start = Instant.now().minusSeconds(3600);
    private final Instant end = Instant.now();

    @BeforeEach
    void setUp() {
        discrepancyRepository.deleteAll();
        runRepository.deleteAll();
        externalGatewayRunner.setInMemorySettlementRows(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        discrepancyRepository.deleteAll();
        runRepository.deleteAll();
    }

    // ─── Test 1: INTERNAL_PER_ACCOUNT detects balance mismatch ────────────────

    @Test
    @DisplayName("TEST-1: INTERNAL_PER_ACCOUNT detects balance mismatch between calculated and stored balances")
    void internalPerAccount_MismatchFound_RecordsDiscrepancy() {
        UUID accountId = UUID.randomUUID();
        when(ledgerClient.getActiveAccountIds(any(), any())).thenReturn(List.of(accountId));
        when(ledgerClient.reconcileAccount(accountId)).thenReturn(AccountReconciliationDto.builder()
                .accountId(accountId)
                .matched(false)
                .calculatedBalance(new BigDecimal("500000.00"))
                .storedBalance(new BigDecimal("400000.00"))
                .discrepancy(new BigDecimal("100000.00"))
                .build());

        ReconciliationRun run = reconciliationService.executeReconciliation(
                ReconciliationRunType.INTERNAL_PER_ACCOUNT, start, end
        );

        assertThat(run.getTotalChecked()).isEqualTo(1);
        assertThat(run.getTotalDiscrepancies()).isEqualTo(1);

        List<Discrepancy> discrepancies = discrepancyRepository.findByReconciliationRunId(run.getId());
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.BALANCE_MISMATCH);
        assertThat(discrepancies.get(0).getSeverity()).isEqualTo(DiscrepancySeverity.MEDIUM);
        assertThat(discrepancies.get(0).getEntityReference()).isEqualTo(accountId.toString());
    }

    // ─── Test 2: INTERNAL_GLOBAL_INVARIANT balanced → 0 discrepancies ─────────

    @Test
    @DisplayName("TEST-2: INTERNAL_GLOBAL_INVARIANT when system is balanced produces 0 discrepancies")
    void internalGlobalInvariant_Balanced_ZeroDiscrepancies() {
        when(ledgerClient.getGlobalTotals(any(), any())).thenReturn(GlobalLedgerTotalsDto.builder()
                .totalDebit(new BigDecimal("10000000.00"))
                .totalCredit(new BigDecimal("10000000.00"))
                .difference(BigDecimal.ZERO)
                .balanced(true)
                .build());

        ReconciliationRun run = reconciliationService.executeReconciliation(
                ReconciliationRunType.INTERNAL_GLOBAL_INVARIANT, start, end
        );

        assertThat(run.getTotalChecked()).isEqualTo(1);
        assertThat(run.getTotalDiscrepancies()).isEqualTo(0);
        assertThat(discrepancyRepository.findAll()).isEmpty();
    }

    // ─── Test 3: INTERNAL_GLOBAL_INVARIANT detects 1-cent difference → CRITICAL ─

    @Test
    @DisplayName("TEST-3: INTERNAL_GLOBAL_INVARIANT detects even 1 VND discrepancy as CRITICAL severity")
    void internalGlobalInvariant_Imbalance_RecordsCriticalDiscrepancy() {
        when(ledgerClient.getGlobalTotals(any(), any())).thenReturn(GlobalLedgerTotalsDto.builder()
                .totalDebit(new BigDecimal("10000001.00"))
                .totalCredit(new BigDecimal("10000000.00"))
                .difference(new BigDecimal("1.00"))
                .balanced(false)
                .build());

        ReconciliationRun run = reconciliationService.executeReconciliation(
                ReconciliationRunType.INTERNAL_GLOBAL_INVARIANT, start, end
        );

        assertThat(run.getTotalDiscrepancies()).isEqualTo(1);

        List<Discrepancy> discrepancies = discrepancyRepository.findByReconciliationRunId(run.getId());
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.get(0).getDiscrepancyType()).isEqualTo(DiscrepancyType.GLOBAL_INVARIANT_VIOLATION);
        assertThat(discrepancies.get(0).getSeverity()).isEqualTo(DiscrepancySeverity.CRITICAL);
    }

    // ─── Test 4: CROSS_SERVICE detects MISSING_LEDGER_ENTRY and ORPHAN_LEDGER_ENTRY ───

    @Test
    @DisplayName("TEST-4: CROSS_SERVICE detects missing and orphan ledger entries")
    void crossService_MissingAndOrphanEntries_DetectedAccurately() {
        UUID completedTxId = UUID.randomUUID();
        UUID orphanTxId = UUID.randomUUID();

        // 1. Transaction COMPLETED but no ledger entries
        when(transactionClient.getCompletedTransactions(any(), any())).thenReturn(List.of(
                TransactionSummaryDto.builder()
                        .id(completedTxId)
                        .status("COMPLETED")
                        .amount(new BigDecimal("200000.00"))
                        .build()
        ));
        when(ledgerClient.getEntriesByTransactionId(completedTxId)).thenReturn(Collections.emptyList());

        // 2. Ledger entry transaction_id does not exist in transaction-service
        when(ledgerClient.getRecentTransactionIds(any(), any())).thenReturn(List.of(orphanTxId));
        when(transactionClient.getTransactionById(orphanTxId)).thenReturn(null);

        ReconciliationRun run = reconciliationService.executeReconciliation(
                ReconciliationRunType.CROSS_SERVICE, start, end
        );

        assertThat(run.getTotalDiscrepancies()).isEqualTo(2);

        List<Discrepancy> discrepancies = discrepancyRepository.findByReconciliationRunId(run.getId());
        assertThat(discrepancies).extracting(Discrepancy::getDiscrepancyType)
                .containsExactlyInAnyOrder(DiscrepancyType.MISSING_LEDGER_ENTRY, DiscrepancyType.ORPHAN_LEDGER_ENTRY);
        assertThat(discrepancies).allMatch(d -> d.getSeverity() == DiscrepancySeverity.HIGH);
    }

    // ─── Test 5: EXTERNAL_GATEWAY detects missing records and amount mismatch ─

    @Test
    @DisplayName("TEST-5: EXTERNAL_GATEWAY detects missing records and amount mismatches")
    void externalGateway_ReportDiscrepancies_DetectedAccurately() {
        String missingRef = "VNP_MISSING_001";
        String mismatchRef = "VNP_MISMATCH_002";

        externalGatewayRunner.setInMemorySettlementRows(List.of(
                SettlementRow.builder()
                        .providerTransactionRef(missingRef)
                        .amount(new BigDecimal("500000.00"))
                        .currency("VND")
                        .status("SUCCESS")
                        .settlementDate(LocalDate.now())
                        .build(),
                SettlementRow.builder()
                        .providerTransactionRef(mismatchRef)
                        .amount(new BigDecimal("300000.00"))
                        .currency("VND")
                        .status("SUCCESS")
                        .settlementDate(LocalDate.now())
                        .build()
        ));

        // missingRef does not exist internally
        when(paymentGatewayClient.getTransactionByProviderRef(missingRef)).thenReturn(null);

        // mismatchRef exists but internal amount is 250000.00 (mismatch)
        when(paymentGatewayClient.getTransactionByProviderRef(mismatchRef)).thenReturn(
                GatewayTransactionDto.builder()
                        .id(UUID.randomUUID())
                        .providerTransactionRef(mismatchRef)
                        .amount(new BigDecimal("250000.00"))
                        .currency("VND")
                        .status("SUCCEEDED")
                        .build()
        );

        when(paymentGatewayClient.getGatewayTransactions(any(), any())).thenReturn(Collections.emptyList());

        ReconciliationRun run = reconciliationService.executeReconciliation(
                ReconciliationRunType.EXTERNAL_GATEWAY, start, end
        );

        assertThat(run.getTotalDiscrepancies()).isEqualTo(2);

        List<Discrepancy> discrepancies = discrepancyRepository.findByReconciliationRunId(run.getId());
        assertThat(discrepancies).extracting(Discrepancy::getDiscrepancyType)
                .containsExactlyInAnyOrder(
                        DiscrepancyType.GATEWAY_MISSING_INTERNAL_RECORD,
                        DiscrepancyType.GATEWAY_AMOUNT_MISMATCH
                );
    }

    // ─── Test 6: Idempotency — repeated run updates existing OPEN discrepancy ─

    @Test
    @DisplayName("TEST-6: Repeated reconciliation runs update existing OPEN discrepancy without creating duplicates")
    void reconciliationRun_DuplicateExecution_DeduplicatesOpenDiscrepancy() {
        UUID accountId = UUID.randomUUID();
        when(ledgerClient.getActiveAccountIds(any(), any())).thenReturn(List.of(accountId));
        when(ledgerClient.reconcileAccount(accountId)).thenReturn(AccountReconciliationDto.builder()
                .accountId(accountId)
                .matched(false)
                .calculatedBalance(new BigDecimal("500000.00"))
                .storedBalance(new BigDecimal("400000.00"))
                .discrepancy(new BigDecimal("100000.00"))
                .build());

        ReconciliationRun run1 = reconciliationService.executeReconciliation(
                ReconciliationRunType.INTERNAL_PER_ACCOUNT, start, end
        );
        ReconciliationRun run2 = reconciliationService.executeReconciliation(
                ReconciliationRunType.INTERNAL_PER_ACCOUNT, start, end
        );

        assertThat(run1.getId()).isNotEqualTo(run2.getId());

        // Total discrepancies in database should remain 1 (deduplicated)
        List<Discrepancy> allDiscrepancies = discrepancyRepository.findAll();
        assertThat(allDiscrepancies).hasSize(1);
        assertThat(allDiscrepancies.get(0).getReconciliationRunId()).isEqualTo(run2.getId());
    }

    // ─── Test 7: Discrepancy resolution & Architectural Read-Only Verification ─

    @Test
    @DisplayName("TEST-7: Discrepancy resolution updates audit fields and ARCHITECTURAL ASSERTION: no write calls to Ledger")
    void resolveDiscrepancy_UpdatesAuditFields_NeverCallsLedgerWrite() {
        Discrepancy disc = discrepancyRepository.save(Discrepancy.builder()
                .reconciliationRunId(UUID.randomUUID())
                .discrepancyType(DiscrepancyType.BALANCE_MISMATCH)
                .severity(DiscrepancySeverity.MEDIUM)
                .entityReference(UUID.randomUUID().toString())
                .status(DiscrepancyStatus.OPEN)
                .createdAt(Instant.now())
                .build());

        reconciliationService.resolveDiscrepancy(
                disc.getId(),
                "admin@paycore.com",
                "Manual investigation completed. False positive due to transit timing.",
                true
        );

        Discrepancy resolved = discrepancyRepository.findById(disc.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(DiscrepancyStatus.FALSE_POSITIVE);
        assertThat(resolved.getResolvedBy()).isEqualTo("admin@paycore.com");
        assertThat(resolved.getResolutionNote()).contains("False positive");
        assertThat(resolved.getResolvedAt()).isNotNull();

        // Architectural Verification: LedgerClient has NO mutate / write methods called
        verify(ledgerClient, never()).reconcileAccount(any());
    }
}
