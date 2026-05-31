package com.paycore.fraudservice.service;

import com.paycore.fraudservice.domain.entity.BlacklistEntry;
import com.paycore.fraudservice.domain.enums.AddedBy;
import com.paycore.fraudservice.domain.enums.EntityType;
import com.paycore.fraudservice.repository.BlacklistEntryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private final BlacklistEntryRepository blacklistEntryRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${fraud.cache.blacklist-prefix:blacklist}")
    private String blacklistPrefix;

    @PostConstruct
    public void init() {
        syncBlacklistToRedis();
    }

    public synchronized void syncBlacklistToRedis() {
        try {
            List<BlacklistEntry> activeEntries = blacklistEntryRepository.findByActiveTrue();
            for (EntityType type : EntityType.values()) {
                String setKey = getRedisKey(type);
                redisTemplate.delete(setKey);
            }

            for (BlacklistEntry entry : activeEntries) {
                if (entry.getExpiresAt() == null || entry.getExpiresAt().isAfter(Instant.now())) {
                    String setKey = getRedisKey(entry.getEntityType());
                    redisTemplate.opsForSet().add(setKey, entry.getEntityValue());
                }
            }
            log.info("Blacklist synchronized to Redis with {} active entries", activeEntries.size());
        } catch (Exception e) {
            log.error("Failed to sync blacklist entries to Redis", e);
        }
    }

    /**
     * Fast O(1) Redis set check for blacklist.
     * Returns matching reason code if found, or empty if clean.
     */
    public Optional<String> checkBlacklist(UUID accountId, String deviceFingerprint, String ipAddress) {
        try {
            if (accountId != null && isMember(EntityType.ACCOUNT, accountId.toString())) {
                return Optional.of("BLACKLISTED_ACCOUNT");
            }
            if (deviceFingerprint != null && !deviceFingerprint.isBlank() && isMember(EntityType.DEVICE, deviceFingerprint)) {
                return Optional.of("BLACKLISTED_DEVICE");
            }
            if (ipAddress != null && !ipAddress.isBlank() && isMember(EntityType.IP, ipAddress)) {
                return Optional.of("BLACKLISTED_IP");
            }
        } catch (Exception e) {
            log.warn("Redis error during blacklist check, skipping blacklist fast-fail: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private boolean isMember(EntityType type, String value) {
        Boolean member = redisTemplate.opsForSet().isMember(getRedisKey(type), value);
        return Boolean.TRUE.equals(member);
    }

    @Transactional
    public BlacklistEntry addEntry(EntityType type, String value, String reason, AddedBy addedBy, Instant expiresAt) {
        Optional<BlacklistEntry> existing = blacklistEntryRepository.findByEntityTypeAndEntityValue(type, value);
        BlacklistEntry entry;
        if (existing.isPresent()) {
            entry = existing.get();
            entry.setActive(true);
            entry.setReason(reason);
            entry.setAddedBy(addedBy);
            entry.setExpiresAt(expiresAt);
        } else {
            entry = BlacklistEntry.builder()
                    .entityType(type)
                    .entityValue(value)
                    .reason(reason)
                    .addedBy(addedBy)
                    .active(true)
                    .expiresAt(expiresAt)
                    .build();
        }
        entry = blacklistEntryRepository.save(entry);

        // Immediately update Redis Set
        try {
            redisTemplate.opsForSet().add(getRedisKey(type), value);
        } catch (Exception e) {
            log.error("Failed to add entry to Redis set: type={}, value={}", type, value, e);
        }

        log.info("Blacklist entry added: type={}, value={}, addedBy={}", type, value, addedBy);
        return entry;
    }

    @Transactional
    public void removeEntry(EntityType type, String value) {
        Optional<BlacklistEntry> existing = blacklistEntryRepository.findByEntityTypeAndEntityValueAndActiveTrue(type, value);
        if (existing.isPresent()) {
            BlacklistEntry entry = existing.get();
            entry.setActive(false);
            blacklistEntryRepository.save(entry);
        }

        try {
            redisTemplate.opsForSet().remove(getRedisKey(type), value);
        } catch (Exception e) {
            log.error("Failed to remove entry from Redis set: type={}, value={}", type, value, e);
        }

        log.info("Blacklist entry removed: type={}, value={}", type, value);
    }

    public List<BlacklistEntry> getActiveBlacklist() {
        return blacklistEntryRepository.findByActiveTrue();
    }

    private String getRedisKey(EntityType type) {
        return blacklistPrefix + ":" + type.name().toLowerCase();
    }
}
