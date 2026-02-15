package com.paycore.accountservice.service;

import com.paycore.accountservice.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled cleanup of expired and revoked refresh tokens.
 * Runs daily at 02:00 AM to keep the refresh_tokens table lean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 2 * * *") // 02:00 AM daily
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(LocalDateTime.now());
        log.info("Token cleanup: removed {} expired/revoked refresh tokens", deleted);
    }
}
