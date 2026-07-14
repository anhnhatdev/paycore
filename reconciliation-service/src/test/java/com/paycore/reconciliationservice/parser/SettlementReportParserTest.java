package com.paycore.reconciliationservice.parser;

import com.paycore.reconciliationservice.dto.SettlementRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettlementReportParserTest {

    private SettlementReportParser parser;

    @BeforeEach
    void setUp() {
        parser = new SettlementReportParser();
    }

    @Test
    @DisplayName("Parses standard settlement CSV content accurately")
    void parseCsvReport_StandardCsv_ReturnsRows() {
        String csv = """
                provider_transaction_ref,amount,currency,status,settlement_date
                VNP_123456,100000.00,VND,SUCCESS,2026-07-01
                VNP_789012,250000.50,VND,SUCCESS,2026-07-01
                """;

        List<SettlementRow> rows = parser.parseCsvReport(csv);

        assertEquals(2, rows.size());
        assertEquals("VNP_123456", rows.get(0).getProviderTransactionRef());
        assertEquals(new BigDecimal("100000.00"), rows.get(0).getAmount());
        assertEquals("VND", rows.get(0).getCurrency());
        assertEquals("SUCCESS", rows.get(0).getStatus());
        assertEquals(LocalDate.of(2026, 7, 1), rows.get(0).getSettlementDate());
    }

    @Test
    @DisplayName("Handles empty or null CSV gracefully")
    void parseCsvReport_EmptyOrNull_ReturnsEmptyList() {
        assertTrue(parser.parseCsvReport(null).isEmpty());
        assertTrue(parser.parseCsvReport("").isEmpty());
    }
}
