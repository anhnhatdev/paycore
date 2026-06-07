package com.paycore.fraudservice.controller;

import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.engine.RuleSyncManager;
import com.paycore.fraudservice.repository.FraudRuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/internal/v1/fraud/rules")
@RequiredArgsConstructor
@Tag(name = "Fraud Rule Configuration APIs", description = "Admin endpoints for dynamic rule management and threshold adjustments")
public class FraudRuleController {

    private final FraudRuleRepository fraudRuleRepository;
    private final RuleSyncManager ruleSyncManager;

    @GetMapping
    @Operation(summary = "Get all fraud rules", description = "Lists all configured rules and their parameters")
    public ResponseEntity<List<FraudRule>> getAllRules() {
        return ResponseEntity.ok(fraudRuleRepository.findAll());
    }

    @PutMapping("/{ruleCode}")
    @Operation(summary = "Update fraud rule", description = "Updates rule parameters and triggers immediate in-memory cache sync")
    public ResponseEntity<FraudRule> updateRule(
            @PathVariable("ruleCode") String ruleCode,
            @RequestBody UpdateRuleRequest request
    ) {
        FraudRule rule = fraudRuleRepository.findByRuleCode(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("Fraud rule not found: " + ruleCode));

        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        if (request.getParams() != null && !request.getParams().isBlank()) {
            rule.setParams(request.getParams());
        }
        rule.setUpdatedAt(Instant.now());
        rule = fraudRuleRepository.save(rule);

        // Instantly reload in-memory cache
        ruleSyncManager.reloadRules();

        log.info("Fraud rule updated and synced: ruleCode={}, enabled={}", ruleCode, rule.isEnabled());
        return ResponseEntity.ok(rule);
    }

    @Data
    public static class UpdateRuleRequest {
        private Boolean enabled;
        private String params;
    }
}
