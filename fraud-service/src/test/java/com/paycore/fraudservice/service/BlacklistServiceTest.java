package com.paycore.fraudservice.service;

import com.paycore.fraudservice.domain.entity.BlacklistEntry;
import com.paycore.fraudservice.domain.enums.AddedBy;
import com.paycore.fraudservice.domain.enums.EntityType;
import com.paycore.fraudservice.repository.BlacklistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceTest {

    @Mock
    private BlacklistEntryRepository blacklistEntryRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private BlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(blacklistService, "blacklistPrefix", "blacklist");
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    @DisplayName("Matches blacklisted account in Redis and returns reason")
    void checkBlacklist_AccountHit_ReturnsReason() {
        UUID accountId = UUID.randomUUID();
        when(setOperations.isMember("blacklist:account", accountId.toString())).thenReturn(true);

        Optional<String> reason = blacklistService.checkBlacklist(accountId, null, null);

        assertTrue(reason.isPresent());
        assertEquals("BLACKLISTED_ACCOUNT", reason.get());
    }

    @Test
    @DisplayName("Matches blacklisted IP in Redis and returns reason")
    void checkBlacklist_IpHit_ReturnsReason() {
        when(setOperations.isMember("blacklist:ip", "10.0.0.99")).thenReturn(true);

        Optional<String> reason = blacklistService.checkBlacklist(null, null, "10.0.0.99");

        assertTrue(reason.isPresent());
        assertEquals("BLACKLISTED_IP", reason.get());
    }

    @Test
    @DisplayName("Clean entities return empty optional")
    void checkBlacklist_Clean_ReturnsEmpty() {
        UUID accountId = UUID.randomUUID();
        when(setOperations.isMember(anyString(), anyString())).thenReturn(false);

        Optional<String> reason = blacklistService.checkBlacklist(accountId, "device-1", "1.1.1.1");

        assertTrue(reason.isEmpty());
    }

    @Test
    @DisplayName("Adds blacklist entry to DB and updates Redis Set")
    void addEntry_PersistsAndUpdatesRedis() {
        when(blacklistEntryRepository.findByEntityTypeAndEntityValue(any(), any())).thenReturn(Optional.empty());
        when(blacklistEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlacklistEntry entry = blacklistService.addEntry(
                EntityType.DEVICE,
                "bad-device-id",
                "Fraudulent device pattern",
                AddedBy.ADMIN_MANUAL,
                null
        );

        assertNotNull(entry);
        assertEquals("bad-device-id", entry.getEntityValue());
        verify(setOperations).add("blacklist:device", "bad-device-id");
    }
}
