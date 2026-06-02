package com.paycore.fraudservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.fraudservice.domain.entity.FraudCheckLog;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.domain.enums.ReviewDecision;
import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.dto.FraudCheckResponse;
import com.paycore.fraudservice.engine.RuleEngine;
import com.paycore.fraudservice.engine.dto.RuleEvaluationResult;
import com.paycore.fraudservice.repository.FraudCheckLogRepository;
import com.paycore.fraudservice.service.BlacklistService;
import com.paycore.fraudservice.service.DedupService;
import com.paycore.fraudservice.service.FraudService;
import com.paycore.fraudservice.service.VelocityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudServiceImpl implements FraudService {

    private final RuleEngine ruleEngine;
    private final BlacklistService blacklistService;
    private final VelocityService velocityService;
    private final DedupService dedupService;
    private final FraudCheckLogRepository fraudCheckLogRepository;
    private final ObjectMapper objectMapper;
    private final Optional<KafkaTemplate<String, String>> kafkaTemplate;

    @Value("${fraud.latency.budget-ms:1200}")
    private long latencyBudgetMs;

    @Value("${fraud.kafka.review-decision-topic:fraud.review-decisions}")
    private String reviewDecisionTopic;

    @Override
    @Transactional
    public FraudCheckResponse evaluateTransaction(FraudCheckRequest request) {
        long startTime = System.currentTimeMillis();
        UUID txId = request.getTransactionId();

        // 1. Double-Count / Retry Dedup Check
        boolean isFirstAttempt = dedupService.tryAcquireDedup(txId);
        if (!isFirstAttempt) {
            Optional<FraudCheckLog> existingLog = fraudCheckLogRepository.findByTransactionId(txId);
            if (existingLog.isPresent()) {
                FraudCheckLog logEntry = existingLog.get();
                List<String> reasons = parseReasonCodes(logEntry.getReasonCodes());
                log.info("Dedup hit: returning cached decision for txId={}, decision={}", txId, logEntry.getDecision());
                return FraudCheckResponse.builder()
                        .checkId(logEntry.getId())
                        .decision(logEntry.getDecision())
                        .reasonCodes(reasons)
                        .build();
            }
        }

        List<RuleEvaluationResult> evaluatedRules = new ArrayList<>();
        List<String> reasonCodes = new ArrayList<>();

        // 2. Blacklist Check (Fail Fast O(1) Redis Lookup)
        Optional<String> blacklistReason = blacklistService.checkBlacklist(
                request.getFromAccountId(),
                request.getDeviceFingerprint(),
                request.getIpAddress()
        );

        if (blacklistReason.isPresent()) {
            String code = blacklistReason.get();
            reasonCodes.add(code);
            evaluatedRules.add(RuleEvaluationResult.builder()
                    .ruleCode("BLACKLIST_CHECK")
                    .passed(false)
                    .suggestedDecision(FraudDecision.REJECT)
                    .reasonCode(code)
                    .build());

            return persistAndBuildResponse(txId, FraudDecision.REJECT, reasonCodes, evaluatedRules, startTime);
        }

        // 3. Latency Budget Check
        if (isBudgetExceeded(startTime)) {
            return fallbackTimeoutResponse(txId, evaluatedRules, startTime);
        }

        // 4. In-Memory Static Rule Evaluation (Amount per KYC, Large Amount)
        List<RuleEvaluationResult> staticResults = ruleEngine.evaluateStaticRules(request);
        evaluatedRules.addAll(staticResults);
        for (RuleEvaluationResult res : staticResults) {
            if (!res.isPassed()) {
                reasonCodes.add(res.getReasonCode());
            }
        }

        // 5. Latency Budget Check before Redis Velocity
        if (isBudgetExceeded(startTime)) {
            return fallbackTimeoutResponse(txId, evaluatedRules, startTime);
        }

        // 6. Sliding Window Velocity Checks
        List<RuleEvaluationResult> velocityResults = velocityService.checkAndIncrementVelocity(request.getFromAccountId());
        evaluatedRules.addAll(velocityResults);
        for (RuleEvaluationResult res : velocityResults) {
            if (!res.isPassed()) {
                reasonCodes.add(res.getReasonCode());
            }
        }

        // 7. Aggregate Decisions
        FraudDecision finalDecision = FraudDecision.ALLOW;
        for (RuleEvaluationResult res : evaluatedRules) {
            if (res.getSuggestedDecision() == FraudDecision.REJECT) {
                finalDecision = FraudDecision.REJECT;
                break;
            } else if (res.getSuggestedDecision() == FraudDecision.REVIEW) {
                finalDecision = FraudDecision.REVIEW;
            }
        }

        return persistAndBuildResponse(txId, finalDecision, reasonCodes, evaluatedRules, startTime);
    }

    private boolean isBudgetExceeded(long startTime) {
        return (System.currentTimeMillis() - startTime) >= latencyBudgetMs;
    }

    private FraudCheckResponse fallbackTimeoutResponse(UUID txId, List<RuleEvaluationResult> evaluatedRules, long startTime) {
        log.warn("Internal latency budget exceeded for txId={}, falling back to REVIEW", txId);
        List<String> reasons = List.of("INTERNAL_TIMEOUT_PARTIAL_CHECK");
        evaluatedRules.add(RuleEvaluationResult.builder()
                .ruleCode("LATENCY_BUDGET_GUARD")
                .passed(false)
                .suggestedDecision(FraudDecision.REVIEW)
                .reasonCode("INTERNAL_TIMEOUT_PARTIAL_CHECK")
                .build());
        return persistAndBuildResponse(txId, FraudDecision.REVIEW, reasons, evaluatedRules, startTime);
    }

    private FraudCheckResponse persistAndBuildResponse(
            UUID txId,
            FraudDecision decision,
            List<String> reasonCodes,
            List<RuleEvaluationResult> evaluatedRules,
            long startTime
    ) {
        int latencyMs = (int) (System.currentTimeMillis() - startTime);
        String evaluatedJson = null;
        try {
            evaluatedJson = objectMapper.writeValueAsString(evaluatedRules);
        } catch (Exception e) {
            log.error("Failed to serialize evaluated rules for txId: {}", txId, e);
        }

        String reasonCodesStr = String.join(",", reasonCodes);

        FraudCheckLog checkLog = FraudCheckLog.builder()
                .transactionId(txId)
                .decision(decision)
                .reasonCodes(reasonCodesStr)
                .rulesEvaluated(evaluatedJson)
                .latencyMs(latencyMs)
                .build();

        checkLog = fraudCheckLogRepository.save(checkLog);

        log.info("Fraud check completed: txId={}, decision={}, reasons={}, latency={}ms",
                txId, decision, reasonCodes, latencyMs);

        return FraudCheckResponse.builder()
                .checkId(checkLog.getId())
                .decision(decision)
                .reasonCodes(reasonCodes)
                .build();
    }

    @Override
    public List<FraudCheckLog> getReviewQueue() {
        return fraudCheckLogRepository.findByDecisionAndReviewDecisionIsNull(FraudDecision.REVIEW);
    }

    @Override
    @Transactional
    public FraudCheckLog decideReview(UUID checkId, UUID reviewerId, ReviewDecision decision, String notes) {
        FraudCheckLog checkLog = fraudCheckLogRepository.findById(checkId)
                .orElseThrow(() -> new IllegalArgumentException("Fraud check log not found: " + checkId));

        if (checkLog.getDecision() != FraudDecision.REVIEW) {
            throw new IllegalStateException("Only REVIEW decisions can be manually resolved. Current decision: " + checkLog.getDecision());
        }

        checkLog.setReviewerId(reviewerId);
        checkLog.setReviewDecision(decision);
        checkLog.setReviewedAt(Instant.now());
        checkLog = fraudCheckLogRepository.save(checkLog);

        log.info("Manual review decision applied: checkId={}, reviewerId={}, decision={}", checkId, reviewerId, decision);

        // Publish event to Kafka if available
        if (kafkaTemplate.isPresent()) {
            try {
                String payload = objectMapper.writeValueAsString(Map.of(
                        "checkId", checkId,
                        "transactionId", checkLog.getTransactionId(),
                        "reviewerId", reviewerId,
                        "decision", decision.name(),
                        "reviewedAt", checkLog.getReviewedAt().toString(),
                        "notes", notes != null ? notes : ""
                ));
                kafkaTemplate.get().send(reviewDecisionTopic, checkLog.getTransactionId().toString(), payload);
            } catch (Exception e) {
                log.error("Failed to publish review decision event for checkId: {}", checkId, e);
            }
        }

        return checkLog;
    }

    private List<String> parseReasonCodes(String reasonCodesStr) {
        if (reasonCodesStr == null || reasonCodesStr.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(reasonCodesStr.split(","));
    }
}
