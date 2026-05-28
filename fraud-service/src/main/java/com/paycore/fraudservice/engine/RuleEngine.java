package com.paycore.fraudservice.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.domain.enums.KycStatus;
import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.engine.dto.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final ObjectMapper objectMapper;
    private final Map<String, FraudRule> inMemoryRules = new ConcurrentHashMap<>();

    public void updateRules(List<FraudRule> rules) {
        inMemoryRules.clear();
        for (FraudRule rule : rules) {
            if (rule.isEnabled()) {
                inMemoryRules.put(rule.getRuleCode(), rule);
            }
        }
        log.info("RuleEngine updated in-memory rules count: {}", inMemoryRules.size());
    }

    public List<FraudRule> getActiveRules() {
        return new ArrayList<>(inMemoryRules.values());
    }

    public Optional<FraudRule> getRule(String ruleCode) {
        return Optional.ofNullable(inMemoryRules.get(ruleCode));
    }

    public List<RuleEvaluationResult> evaluateStaticRules(FraudCheckRequest request) {
        List<RuleEvaluationResult> results = new ArrayList<>();

        // 1. Evaluate Max Amount for Unverified/Pending KYC
        FraudRule unverifiedRule = inMemoryRules.get("MAX_AMOUNT_PER_TX_UNVERIFIED");
        if (unverifiedRule != null && unverifiedRule.isEnabled()) {
            if (request.getKycStatus() == null || request.getKycStatus() == KycStatus.PENDING) {
                Map<String, Object> params = parseParams(unverifiedRule.getParams());
                BigDecimal maxAmount = extractBigDecimal(params, "maxAmount", new BigDecimal("5000000.00"));
                if (request.getAmount().compareTo(maxAmount) > 0) {
                    results.add(RuleEvaluationResult.builder()
                            .ruleCode(unverifiedRule.getRuleCode())
                            .passed(false)
                            .suggestedDecision(FraudDecision.REJECT)
                            .reasonCode("AMOUNT_ABOVE_UNVERIFIED_LIMIT")
                            .details(Map.of("amount", request.getAmount(), "maxAllowed", maxAmount))
                            .build());
                }
            }
        }

        // 2. Evaluate Max Amount for Verified KYC
        FraudRule verifiedRule = inMemoryRules.get("MAX_AMOUNT_PER_TX_VERIFIED");
        if (verifiedRule != null && verifiedRule.isEnabled()) {
            if (request.getKycStatus() == KycStatus.VERIFIED) {
                Map<String, Object> params = parseParams(verifiedRule.getParams());
                BigDecimal maxAmount = extractBigDecimal(params, "maxAmount", new BigDecimal("50000000.00"));
                if (request.getAmount().compareTo(maxAmount) > 0) {
                    results.add(RuleEvaluationResult.builder()
                            .ruleCode(verifiedRule.getRuleCode())
                            .passed(false)
                            .suggestedDecision(FraudDecision.REJECT)
                            .reasonCode("MAX_AMOUNT_EXCEEDED")
                            .details(Map.of("amount", request.getAmount(), "maxAllowed", maxAmount))
                            .build());
                }
            }
        }

        // 3. Evaluate Large Amount for Manual Review
        FraudRule largeAmountRule = inMemoryRules.get("LARGE_AMOUNT_REVIEW");
        if (largeAmountRule != null && largeAmountRule.isEnabled()) {
            Map<String, Object> params = parseParams(largeAmountRule.getParams());
            BigDecimal threshold = extractBigDecimal(params, "threshold", new BigDecimal("30000000.00"));
            if (request.getAmount().compareTo(threshold) >= 0) {
                results.add(RuleEvaluationResult.builder()
                        .ruleCode(largeAmountRule.getRuleCode())
                        .passed(false)
                        .suggestedDecision(FraudDecision.REVIEW)
                        .reasonCode("LARGE_TRANSACTION_AMOUNT")
                        .details(Map.of("amount", request.getAmount(), "reviewThreshold", threshold))
                        .build());
            }
        }

        return results;
    }

    public Map<String, Object> parseParams(String jsonParams) {
        if (jsonParams == null || jsonParams.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(jsonParams, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse rule params: {}", jsonParams, e);
            return Collections.emptyMap();
        }
    }

    private BigDecimal extractBigDecimal(Map<String, Object> params, String key, BigDecimal defaultVal) {
        Object val = params.get(key);
        if (val == null) return defaultVal;
        try {
            return new BigDecimal(val.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
