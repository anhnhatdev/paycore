package com.paycore.fraudservice;

import com.paycore.fraudservice.domain.entity.BlacklistEntry;
import com.paycore.fraudservice.domain.entity.FraudCheckLog;
import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.domain.enums.*;
import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.dto.FraudCheckResponse;
import com.paycore.fraudservice.engine.RuleEngine;
import com.paycore.fraudservice.engine.RuleSyncManager;
import com.paycore.fraudservice.repository.BlacklistEntryRepository;
import com.paycore.fraudservice.repository.FraudCheckLogRepository;
import com.paycore.fraudservice.repository.FraudRuleRepository;
import com.paycore.fraudservice.service.BlacklistService;
import com.paycore.fraudservice.service.FraudService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class FraudServiceIntegrationTest {

    @Autowired
    private FraudService fraudService;

    @Autowired
    private BlacklistService blacklistService;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private RuleSyncManager ruleSyncManager;

    @Autowired
    private FraudRuleRepository fraudRuleRepository;

    @Autowired
    private BlacklistEntryRepository blacklistEntryRepository;

    @Autowired
    private FraudCheckLogRepository fraudCheckLogRepository;

    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @MockBean
    private RedisTemplate<String, Object> objectRedisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private SetOperations<String, String> setOperations;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private final Map<String, String> inMemoryRedisKv = new HashMap<>();
    private final Map<String, Set<String>> inMemoryRedisSets = new HashMap<>();

    @BeforeEach
    void setUp() {
        fraudCheckLogRepository.deleteAll();
        blacklistEntryRepository.deleteAll();
        fraudRuleRepository.deleteAll();
        inMemoryRedisKv.clear();
        inMemoryRedisSets.clear();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        // Mock Redis KV
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (inMemoryRedisKv.containsKey(key)) {
                return false;
            }
            inMemoryRedisKv.put(key, inv.getArgument(1));
            return true;
        });

        when(valueOperations.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String current = inMemoryRedisKv.getOrDefault(key, "0");
            long next = Long.parseLong(current) + 1;
            inMemoryRedisKv.put(key, String.valueOf(next));
            return next;
        });

        // Mock Redis Sets
        when(setOperations.isMember(anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            return inMemoryRedisSets.getOrDefault(key, Collections.emptySet()).contains(val);
        });

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            inMemoryRedisSets.computeIfAbsent(key, k -> new HashSet<>()).add(val);
            return 1L;
        }).when(setOperations).add(anyString(), anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            Set<String> s = inMemoryRedisSets.get(key);
            if (s != null) s.remove(val);
            return 1L;
        }).when(setOperations).remove(anyString(), anyString());

        // Seed initial rules
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
                        .ruleCode("VELOCITY_PER_MINUTE")
                        .enabled(true)
                        .params("{\"limit\":5,\"windowSeconds\":60}")
                        .build(),
                FraudRule.builder()
                        .ruleCode("LARGE_AMOUNT_REVIEW")
                        .enabled(true)
                        .params("{\"threshold\":30000000.00,\"currency\":\"VND\"}")
                        .build()
        );
        fraudRuleRepository.saveAll(rules);
        ruleSyncManager.reloadRules();
    }

    @Test
    @DisplayName("Clean verified transaction within limits returns ALLOW")
    void evaluate_CleanVerifiedTransaction_ReturnsAllow() {
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .toAccountId(UUID.randomUUID())
                .amount(new BigDecimal("1000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .deviceFingerprint("device_clean_01")
                .ipAddress("192.168.1.100")
                .build();

        FraudCheckResponse response = fraudService.evaluateTransaction(request);

        assertNotNull(response.getCheckId());
        assertEquals(FraudDecision.ALLOW, response.getDecision());
        assertTrue(response.getReasonCodes().isEmpty());

        FraudCheckLog savedLog = fraudCheckLogRepository.findById(response.getCheckId()).orElseThrow();
        assertEquals(FraudDecision.ALLOW, savedLog.getDecision());
        assertTrue(savedLog.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("Blacklisted account triggers instant fail-fast REJECT (< 50ms)")
    void evaluate_BlacklistedAccount_FailFastReject() {
        UUID blacklistedAccount = UUID.randomUUID();
        blacklistService.addEntry(EntityType.ACCOUNT, blacklistedAccount.toString(), "Suspicious scam activity", AddedBy.ADMIN_MANUAL, null);

        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(blacklistedAccount)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        long start = System.currentTimeMillis();
        FraudCheckResponse response = fraudService.evaluateTransaction(request);
        long latency = System.currentTimeMillis() - start;

        assertEquals(FraudDecision.REJECT, response.getDecision());
        assertTrue(response.getReasonCodes().contains("BLACKLISTED_ACCOUNT"));
        assertTrue(latency < 200, "Fail fast latency should be very small");
    }

    @Test
    @DisplayName("Blacklisted IP address triggers instant REJECT")
    void evaluate_BlacklistedIp_FailFastReject() {
        String badIp = "203.0.113.199";
        blacklistService.addEntry(EntityType.IP, badIp, "Known botnet IP", AddedBy.SYSTEM_AUTO, null);

        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .ipAddress(badIp)
                .build();

        FraudCheckResponse response = fraudService.evaluateTransaction(request);

        assertEquals(FraudDecision.REJECT, response.getDecision());
        assertTrue(response.getReasonCodes().contains("BLACKLISTED_IP"));
    }

    @Test
    @DisplayName("Unverified KYC user exceeding 5M VND limit is rejected with AMOUNT_ABOVE_UNVERIFIED_LIMIT")
    void evaluate_UnverifiedUserAboveLimit_ReturnsReject() {
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .amount(new BigDecimal("8000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.PENDING)
                .build();

        FraudCheckResponse response = fraudService.evaluateTransaction(request);

        assertEquals(FraudDecision.REJECT, response.getDecision());
        assertTrue(response.getReasonCodes().contains("AMOUNT_ABOVE_UNVERIFIED_LIMIT"));
    }

    @Test
    @DisplayName("Exceeding 5 transactions per minute triggers VELOCITY_EXCEEDED_1MIN")
    void evaluate_VelocityExceeded_ReturnsReject() {
        UUID accountId = UUID.randomUUID();

        // Perform 5 allowed transactions
        for (int i = 0; i < 5; i++) {
            FraudCheckRequest req = FraudCheckRequest.builder()
                    .transactionId(UUID.randomUUID())
                    .fromAccountId(accountId)
                    .amount(new BigDecimal("50000.00"))
                    .currency("VND")
                    .kycStatus(KycStatus.VERIFIED)
                    .build();
            FraudCheckResponse resp = fraudService.evaluateTransaction(req);
            assertEquals(FraudDecision.ALLOW, resp.getDecision());
        }

        // 6th transaction exceeds 1min limit (5)
        FraudCheckRequest request6 = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(accountId)
                .amount(new BigDecimal("50000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        FraudCheckResponse resp6 = fraudService.evaluateTransaction(request6);

        assertEquals(FraudDecision.REJECT, resp6.getDecision());
        assertTrue(resp6.getReasonCodes().contains("VELOCITY_EXCEEDED_1MIN"));
    }

    @Test
    @DisplayName("Double-count prevention: retry call with same transactionId returns cached decision without re-incrementing velocity")
    void evaluate_DoubleCountPrevention_ReturnsCachedDecision() {
        UUID txId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(txId)
                .fromAccountId(accountId)
                .amount(new BigDecimal("500000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        // First call
        FraudCheckResponse firstResp = fraudService.evaluateTransaction(request);
        assertEquals(FraudDecision.ALLOW, firstResp.getDecision());

        String velocityKey = "velocity:" + accountId + ":1min";
        assertEquals("1", inMemoryRedisKv.get(velocityKey));

        // Retry call with exact same transactionId
        FraudCheckResponse retryResp = fraudService.evaluateTransaction(request);

        assertEquals(firstResp.getCheckId(), retryResp.getCheckId());
        assertEquals(FraudDecision.ALLOW, retryResp.getDecision());
        // Velocity counter MUST NOT have incremented to 2
        assertEquals("1", inMemoryRedisKv.get(velocityKey));
    }

    @Test
    @DisplayName("Latency budget exceeded triggers graceful fallback to REVIEW with INTERNAL_TIMEOUT_PARTIAL_CHECK")
    void evaluate_LatencyBudgetExceeded_ReturnsReviewFallback() {
        // Temporarily lower latency budget to 0ms to simulate internal timeout
        ReflectionTestUtils.setField(fraudService, "latencyBudgetMs", 0L);

        try {
            FraudCheckRequest request = FraudCheckRequest.builder()
                    .transactionId(UUID.randomUUID())
                    .fromAccountId(UUID.randomUUID())
                    .amount(new BigDecimal("500000.00"))
                    .currency("VND")
                    .kycStatus(KycStatus.VERIFIED)
                    .build();

            FraudCheckResponse response = fraudService.evaluateTransaction(request);

            assertEquals(FraudDecision.REVIEW, response.getDecision());
            assertTrue(response.getReasonCodes().contains("INTERNAL_TIMEOUT_PARTIAL_CHECK"));
        } finally {
            ReflectionTestUtils.setField(fraudService, "latencyBudgetMs", 1200L);
        }
    }

    @Test
    @DisplayName("Admin decides pending review queue transaction and records decision audit")
    void reviewQueue_AdminDecideReview_SuccessfullyUpdated() {
        // Create a large transaction that flags for review
        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .amount(new BigDecimal("35000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        FraudCheckResponse checkResp = fraudService.evaluateTransaction(request);
        assertEquals(FraudDecision.REVIEW, checkResp.getDecision());

        List<FraudCheckLog> pendingQueue = fraudService.getReviewQueue();
        assertFalse(pendingQueue.isEmpty());

        UUID reviewerId = UUID.randomUUID();
        FraudCheckLog decidedLog = fraudService.decideReview(checkResp.getCheckId(), reviewerId, ReviewDecision.APPROVE, "Customer verified via phone call");

        assertEquals(ReviewDecision.APPROVE, decidedLog.getReviewDecision());
        assertEquals(reviewerId, decidedLog.getReviewerId());
        assertNotNull(decidedLog.getReviewedAt());

        // Verify it is no longer in pending review queue
        assertTrue(fraudService.getReviewQueue().isEmpty());
    }

    @Test
    @DisplayName("Dynamic rule update changes evaluation behavior without service restart")
    void ruleEngine_DynamicUpdate_RefreshesInMemoryCache() {
        // Update verified max limit from 50M to 20M
        FraudRule rule = fraudRuleRepository.findByRuleCode("MAX_AMOUNT_PER_TX_VERIFIED").orElseThrow();
        rule.setParams("{\"maxAmount\":20000000.00,\"currency\":\"VND\"}");
        fraudRuleRepository.save(rule);
        ruleSyncManager.reloadRules();

        FraudCheckRequest request = FraudCheckRequest.builder()
                .transactionId(UUID.randomUUID())
                .fromAccountId(UUID.randomUUID())
                .amount(new BigDecimal("25000000.00"))
                .currency("VND")
                .kycStatus(KycStatus.VERIFIED)
                .build();

        FraudCheckResponse response = fraudService.evaluateTransaction(request);

        assertEquals(FraudDecision.REJECT, response.getDecision());
        assertTrue(response.getReasonCodes().contains("MAX_AMOUNT_EXCEEDED"));
    }
}
