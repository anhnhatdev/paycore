package com.paycore.fraudservice.service;

import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.engine.RuleEngine;
import com.paycore.fraudservice.engine.dto.RuleEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VelocityService {

    private final RedisTemplate<String, String> redisTemplate;
    private final RuleEngine ruleEngine;

    @Value("${fraud.cache.velocity-prefix:velocity}")
    private String velocityPrefix;

    public List<RuleEvaluationResult> checkAndIncrementVelocity(UUID accountId) {
        List<RuleEvaluationResult> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        try {
            // 1. Minute window (1 minute = 60s)
            long count1m = incrementWithTtl(accountId, "1min", Duration.ofSeconds(60));
            FraudRule minuteRule = ruleEngine.getRule("VELOCITY_PER_MINUTE").orElse(null);
            if (minuteRule != null && minuteRule.isEnabled()) {
                Map<String, Object> params = ruleEngine.parseParams(minuteRule.getParams());
                long limit = extractLong(params, "limit", 5L);
                if (count1m > limit) {
                    results.add(RuleEvaluationResult.builder()
                            .ruleCode(minuteRule.getRuleCode())
                            .passed(false)
                            .suggestedDecision(FraudDecision.REJECT)
                            .reasonCode("VELOCITY_EXCEEDED_1MIN")
                            .details(Map.of("count", count1m, "limit", limit, "window", "1min"))
                            .build());
                }
            }

            // 2. Hour window (1 hour = 3600s)
            long count1h = incrementWithTtl(accountId, "1hour", Duration.ofSeconds(3600));
            FraudRule hourRule = ruleEngine.getRule("VELOCITY_PER_HOUR").orElse(null);
            if (hourRule != null && hourRule.isEnabled()) {
                Map<String, Object> params = ruleEngine.parseParams(hourRule.getParams());
                long limit = extractLong(params, "limit", 20L);
                if (count1h > limit) {
                    results.add(RuleEvaluationResult.builder()
                            .ruleCode(hourRule.getRuleCode())
                            .passed(false)
                            .suggestedDecision(FraudDecision.REJECT)
                            .reasonCode("VELOCITY_EXCEEDED_1HOUR")
                            .details(Map.of("count", count1h, "limit", limit, "window", "1hour"))
                            .build());
                }
            }

            // 3. Day window (1 day = 86400s)
            long count1d = incrementWithTtl(accountId, "1day", Duration.ofSeconds(86400));
            FraudRule dayRule = ruleEngine.getRule("VELOCITY_PER_DAY").orElse(null);
            if (dayRule != null && dayRule.isEnabled()) {
                Map<String, Object> params = ruleEngine.parseParams(dayRule.getParams());
                long limit = extractLong(params, "limit", 50L);
                if (count1d > limit) {
                    results.add(RuleEvaluationResult.builder()
                            .ruleCode(dayRule.getRuleCode())
                            .passed(false)
                            .suggestedDecision(FraudDecision.REVIEW)
                            .reasonCode("VELOCITY_EXCEEDED_1DAY")
                            .details(Map.of("count", count1d, "limit", limit, "window", "1day"))
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Redis velocity check failed for accountId: {}", accountId, e);
            // Non-fatal, return whatever partial results were gathered or empty
        }

        return results;
    }

    private long incrementWithTtl(UUID accountId, String window, Duration ttl) {
        String key = velocityPrefix + ":" + accountId + ":" + window;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return count != null ? count : 1L;
    }

    private long extractLong(Map<String, Object> params, String key, long defaultVal) {
        Object val = params.get(key);
        if (val == null) return defaultVal;
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
