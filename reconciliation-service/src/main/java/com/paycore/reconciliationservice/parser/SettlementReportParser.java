package com.paycore.reconciliationservice.parser;

import com.paycore.reconciliationservice.dto.SettlementRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SettlementReportParser {

    /**
     * Parses CSV settlement report content with headers:
     * provider_transaction_ref,amount,currency,status,settlement_date
     */
    public List<SettlementRow> parseCsvReport(String csvContent) {
        List<SettlementRow> rows = new ArrayList<>();
        if (csvContent == null || csvContent.isBlank()) {
            return rows;
        }

        try (Reader reader = new StringReader(csvContent)) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            try (CSVParser parser = new CSVParser(reader, format)) {
                for (CSVRecord record : parser) {
                    try {
                        String ref = record.isMapped("provider_transaction_ref")
                                ? record.get("provider_transaction_ref")
                                : (record.isMapped("ref") ? record.get("ref") : record.get(0));

                        String amountStr = record.isMapped("amount")
                                ? record.get("amount")
                                : record.get(1);

                        String currency = record.isMapped("currency")
                                ? record.get("currency")
                                : (record.size() > 2 ? record.get(2) : "VND");

                        String status = record.isMapped("status")
                                ? record.get("status")
                                : (record.size() > 3 ? record.get(3) : "SUCCESS");

                        String dateStr = record.isMapped("settlement_date")
                                ? record.get("settlement_date")
                                : (record.size() > 4 ? record.get(4) : LocalDate.now().toString());

                        SettlementRow row = SettlementRow.builder()
                                .providerTransactionRef(ref)
                                .amount(new BigDecimal(amountStr))
                                .currency(currency)
                                .status(status)
                                .settlementDate(LocalDate.parse(dateStr))
                                .build();
                        rows.add(row);
                    } catch (Exception rowEx) {
                        log.warn("Skipping invalid CSV record #{}: {}", record.getRecordNumber(), rowEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse settlement CSV report: {}", e.getMessage(), e);
        }
        return rows;
    }
}
