package com.paycore.fraudservice.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.domain.enums.KycStatus;
import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.engine.dto.RuleEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {

    private RuleEngine ruleEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ruleEngine = new RuleEngine(objectMapper);

        List<FraudRule> rules = List.of(
                FraudRule.builder()
                        .ruleCode("MAX_AMOUNT_PER_TX_UNVERIFIED")
                        .enabled(true)
                        .params("{\"maxAmount\":5000000.00,\"currency\":\"VND\"}")
                        .appliesToKycStatus(KycStatus.PENDING)
                        .build(),
                FraudRule.builder()
                        .ruleCode("MAX_AMOUNT_PER_TX_VERIFIED")
                        .enabled(true)
                        .params("{\"maxAmount\":50000000.00,\"currency\":\"VND\"}")
                        .appliesToKycStatus(KycStatus.VERIFIED)
                        .build(),
                FraudRule.builder()
                        .ruleCode("LARGE_AMOUNT_REVIEW")
                        .enabled(true)
                        .params("{\"threshold\":30000000.00,\"currency\":\"VND\"}")
                        .build()
        );
        ruleEngine.updateRules(rules);
    }

    @Test
    @DisplayName("Unverified KYC user with amount > 5M VND is rejected")
    void evaluate_UnverifiedKyc_ExceedsLimit_ReturnsReject() {
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .amount(new BigDecimal("7000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.PENDING)
                .build();

        List<RuleEvaluationResult> results = ruleEngine.evaluateStaticRules(request);

        assertFalse(results.isEmpty());
        RuleEvaluationResult unverifiedResult = results.stream()
                .filter(r -> "MAX_AMOUNT_PER_TX_UNVERIFIED".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();

        assertEquals(FraudDecision.REJECT, unverifiedResult.getSuggestedDecision());
        assertEquals("AMOUNT_ABOVE_UNVERIFIED_LIMIT", unverifiedResult.getReasonCode());
    }

    @Test
    @DisplayName("Verified KYC user with amount within 50M VND passes max limit but flags large amount review if > 30M")
    void evaluate_VerifiedKyc_LargeAmount_ReturnsReview() {
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .amount(new BigDecimal("35000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        List<RuleEvaluationResult> results = ruleEngine.evaluateStaticRules(request);

        assertEquals(1, results.size());
        assertEquals("LARGE_AMOUNT_REVIEW", results.get(0).getRuleCode());
        assertEquals(FraudDecision.REVIEW, results.get(0).getSuggestedDecision());
        assertEquals("LARGE_TRANSACTION_AMOUNT", results.get(0).getReasonCode());
    }

    @Test
    @DisplayName("Verified KYC user exceeding 50M VND is rejected")
    void evaluate_VerifiedKyc_ExceedsMaxLimit_ReturnsReject() {
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .amount(new BigDecimal("60000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        List<RuleEvaluationResult> results = ruleEngine.evaluateStaticRules(request);

        assertTrue(results.stream().anyMatch(r -> "MAX_AMOUNT_EXCEEDED".equals(r.getReasonCode())));
    }
}
