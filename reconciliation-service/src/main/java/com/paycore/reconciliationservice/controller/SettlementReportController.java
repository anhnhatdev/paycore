package com.paycore.reconciliationservice.controller;

import com.paycore.reconciliationservice.domain.entity.SettlementReport;
import com.paycore.reconciliationservice.dto.SettlementRow;
import com.paycore.reconciliationservice.dto.UploadSettlementRequest;
import com.paycore.reconciliationservice.parser.SettlementReportParser;
import com.paycore.reconciliationservice.repository.SettlementReportRepository;
import com.paycore.reconciliationservice.runner.ExternalGatewayRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/internal/v1/reconciliation/settlement")
@RequiredArgsConstructor
@Tag(name = "Settlement Reports", description = "Endpoints for uploading and auditing provider settlement files")
public class SettlementReportController {

    private final SettlementReportRepository settlementReportRepository;
    private final SettlementReportParser settlementReportParser;
    private final ExternalGatewayRunner externalGatewayRunner;

    @PostMapping("/upload")
    @Operation(summary = "Upload and parse settlement report", description = "Stores raw settlement file for audit and pre-buffers rows for external gateway reconciliation")
    public ResponseEntity<SettlementReport> uploadSettlement(@Valid @RequestBody UploadSettlementRequest request) {
        log.info("Uploading settlement report: provider={}, reportDate={}",
                request.getProvider(), request.getReportDate());

        List<SettlementRow> rows = settlementReportParser.parseCsvReport(request.getCsvContent());
        externalGatewayRunner.setInMemorySettlementRows(rows);

        SettlementReport report = settlementReportRepository
                .findByProviderAndReportDate(request.getProvider(), request.getReportDate())
                .orElseGet(() -> SettlementReport.builder()
                        .provider(request.getProvider())
                        .reportDate(request.getReportDate())
                        .build());

        report.setRawFileReference(request.getCsvContent());
        report.setRowCount(rows.size());
        report.setDownloadedAt(Instant.now());

        SettlementReport saved = settlementReportRepository.save(report);
        log.info("Saved settlement report: id={}, rows={}", saved.getId(), rows.size());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/reports")
    @Operation(summary = "List all settlement reports")
    public ResponseEntity<List<SettlementReport>> getReports() {
        return ResponseEntity.ok(settlementReportRepository.findAll());
    }
}
