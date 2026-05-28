package com.paycore.fraudservice.engine.dto;

import com.paycore.fraudservice.domain.enums.FraudDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleEvaluationResult {
    private String ruleCode;
    private boolean passed;
    private FraudDecision suggestedDecision;
    private String reasonCode;
    private Map<String, Object> details;
}
