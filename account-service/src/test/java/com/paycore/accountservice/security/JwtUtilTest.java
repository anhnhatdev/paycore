package com.paycore.accountservice.security;

import com.paycore.accountservice.config.JwtConfig;
import com.paycore.accountservice.entity.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private User testUser;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setPrivateKeyPath("classpath:keys/test-private.pem");
        config.setPublicKeyPath("classpath:keys/test-public.pem");
        config.setAccessTokenExpirationMs(900000); // 15m
        config.setRefreshTokenExpirationMs(604800000); // 7d

        jwtUtil = new JwtUtil(config, new DefaultResourceLoader());
        jwtUtil.init();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test.user@paycore.com")
                .role(User.Role.USER)
                .kycStatus(User.KycStatus.VERIFIED)
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Generate access token should create valid RS256 token with expected claims")
    void generateAccessToken_ShouldContainValidClaims() {
        String token = jwtUtil.generateAccessToken(testUser);

        assertNotNull(token);
        assertTrue(jwtUtil.isTokenValid(token));
        assertEquals(testUser.getId(), jwtUtil.extractUserId(token));
        assertEquals(User.Role.USER.name(), jwtUtil.extractRole(token));

        Claims claims = jwtUtil.validateAndExtractClaims(token);
        assertEquals("ACCESS", claims.get("tokenType"));
        assertEquals(User.KycStatus.VERIFIED.name(), claims.get("kycStatus"));
    }

    @Test
    @DisplayName("Generate refresh token should have unique jti and REFRESH tokenType")
    void generateRefreshToken_ShouldContainValidClaims() {
        String token1 = jwtUtil.generateRefreshToken(testUser);
        String token2 = jwtUtil.generateRefreshToken(testUser);

        assertNotNull(token1);
        assertNotNull(token2);
        assertNotEquals(token1, token2, "Each refresh token must have unique jti ID");

        assertTrue(jwtUtil.isTokenValid(token1));
        assertEquals(testUser.getId(), jwtUtil.extractUserId(token1));

        Claims claims = jwtUtil.validateAndExtractClaims(token1);
        assertEquals("REFRESH", claims.get("tokenType"));
        assertNotNull(claims.getId(), "Refresh token must have jti claim");
    }

    @Test
    @DisplayName("isTokenValid should return false for tampered token")
    void isTokenValid_ShouldReturnFalseForTamperedToken() {
        String token = jwtUtil.generateAccessToken(testUser);
        String tampered = token.substring(0, token.length() - 5) + "abcde";

        assertFalse(jwtUtil.isTokenValid(tampered));
    }
}
