package com.paycore.fraudservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DedupService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${fraud.cache.dedup-prefix:dedup}")
    private String dedupPrefix;

    @Value("${fraud.cache.dedup-ttl-seconds:300}")
    private long dedupTtlSeconds;

    /**
     * Tries to acquire dedup lock for the transactionId.
     * Returns true if newly acquired (first attempt), false if already exists (retry attempt).
     */
    public boolean tryAcquireDedup(UUID transactionId) {
        String key = dedupPrefix + ":" + transactionId;
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    key,
                    "PROCESSED",
                    Duration.ofSeconds(dedupTtlSeconds)
            );
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis error on dedup check for transactionId: {}, allowing execution", transactionId, e);
            return true;
        }
    }
}
