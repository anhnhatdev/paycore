package com.paycore.fraudservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.domain.enums.FraudDecision;
import com.paycore.fraudservice.engine.RuleEngine;
import com.paycore.fraudservice.engine.dto.RuleEvaluationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VelocityServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RuleEngine ruleEngine;

    @InjectMocks
    private VelocityService velocityService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(velocityService, "velocityPrefix", "velocity");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Flags VELOCITY_EXCEEDED_1MIN when count exceeds 1min limit")
    void checkVelocity_Exceeds1MinLimit_ReturnsReject() {
        UUID accountId = UUID.randomUUID();

        // 1min counter returns 6 (limit = 5)
        when(valueOperations.increment(eq("velocity:" + accountId + ":1min"))).thenReturn(6L);
        when(valueOperations.increment(eq("velocity:" + accountId + ":1hour"))).thenReturn(6L);
        when(valueOperations.increment(eq("velocity:" + accountId + ":1day"))).thenReturn(6L);

        FraudRule minRule = FraudRule.builder()
                .ruleCode("VELOCITY_PER_MINUTE")
                .enabled(true)
                .params("{\"limit\":5}")
                .build();
        when(ruleEngine.getRule("VELOCITY_PER_MINUTE")).thenReturn(Optional.of(minRule));
        when(ruleEngine.getRule("VELOCITY_PER_HOUR")).thenReturn(Optional.empty());
        when(ruleEngine.getRule("VELOCITY_PER_DAY")).thenReturn(Optional.empty());
        when(ruleEngine.parseParams(anyString())).thenReturn(java.util.Map.of("limit", 5));

        List<RuleEvaluationResult> results = velocityService.checkAndIncrementVelocity(accountId);

        assertFalse(results.isEmpty());
        assertEquals("VELOCITY_EXCEEDED_1MIN", results.get(0).getReasonCode());
        assertEquals(FraudDecision.REJECT, results.get(0).getSuggestedDecision());
    }
}
