package com.paycore.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtValidatorTest {

    private JwtValidator jwtValidator;
    private PrivateKey testPrivateKey;

    @BeforeEach
    void setUp() throws Exception {
        jwtValidator = new JwtValidator(new DefaultResourceLoader());
        ReflectionTestUtils.setField(jwtValidator, "publicKeyPath", "classpath:keys/test-public.pem");
        jwtValidator.init();

        // Load test private key for token generation
        DefaultResourceLoader loader = new DefaultResourceLoader();
        try (InputStream is = loader.getResource("classpath:keys/test-private.pem").getInputStream()) {
            String keyStr = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

            byte[] keyBytes = Base64.getDecoder().decode(keyStr);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            this.testPrivateKey = kf.generatePrivate(spec);
        }
    }

    private String generateToken(String subject, String tokenType, long expirationMs) {
        return Jwts.builder()
                .subject(subject)
                .claim("tokenType", tokenType)
                .claim("role", "USER")
                .claim("kycStatus", "VERIFIED")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(testPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    @Test
    @DisplayName("Valid ACCESS token should return true")
    void isTokenValid_ValidAccessToken_ReturnsTrue() {
        String token = generateToken(UUID.randomUUID().toString(), "ACCESS", 60000);
        assertTrue(jwtValidator.isTokenValid(token));
    }

    @Test
    @DisplayName("REFRESH token should be rejected by gateway (only ACCESS tokens allowed)")
    void isTokenValid_RefreshToken_ReturnsFalse() {
        String token = generateToken(UUID.randomUUID().toString(), "REFRESH", 60000);
        assertFalse(jwtValidator.isTokenValid(token));
    }

    @Test
    @DisplayName("Expired token should return false")
    void isTokenValid_ExpiredToken_ReturnsFalse() {
        String token = generateToken(UUID.randomUUID().toString(), "ACCESS", -1000);
        assertFalse(jwtValidator.isTokenValid(token));
    }

    @Test
    @DisplayName("Extract claims from valid token should return subject and role")
    void validateAndExtractClaims_Success() {
        String userId = UUID.randomUUID().toString();
        String token = generateToken(userId, "ACCESS", 60000);

        Claims claims = jwtValidator.validateAndExtractClaims(token);
        assertEquals(userId, claims.getSubject());
        assertEquals("USER", claims.get("role"));
        assertEquals("VERIFIED", claims.get("kycStatus"));
    }
}
