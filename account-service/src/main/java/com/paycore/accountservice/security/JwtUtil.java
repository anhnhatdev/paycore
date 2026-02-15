package com.paycore.accountservice.security;

import com.paycore.accountservice.config.JwtConfig;
import com.paycore.accountservice.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT utility using RS256 (asymmetric).
 * <p>
 * - Account Service holds the RSA PRIVATE key → signs tokens.
 * - Other services (Transaction, Ledger) only need the PUBLIC key → can verify but cannot forge tokens.
 * <p>
 * This is the recommended approach over HS256 (shared secret) for multi-service architectures.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtUtil {

    private final JwtConfig jwtConfig;
    private final ResourceLoader resourceLoader;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    @PostConstruct
    public void init() {
        try {
            privateKey = loadPrivateKey(jwtConfig.getPrivateKeyPath());
            publicKey  = loadPublicKey(jwtConfig.getPublicKeyPath());
            log.info("JWT RS256 keys loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load JWT RS256 keys: {}", e.getMessage());
            throw new IllegalStateException("Cannot initialize JWT key pair", e);
        }
    }

    // -------------------------------------------------------------------------
    // Token Generation
    // -------------------------------------------------------------------------

    /**
     * Generate a short-lived access token (15 min).
     * Payload: sub=userId, role, kycStatus.
     */
    public String generateAccessToken(User user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getAccessTokenExpirationMs());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("kycStatus", user.getKycStatus().name())
                .claim("tokenType", "ACCESS")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Generate a long-lived refresh token (7 days).
     * Payload: sub=userId, jti=unique token ID for revocation tracking.
     */
    public String generateRefreshToken(User user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getRefreshTokenExpirationMs());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("tokenType", "REFRESH")
                .id(UUID.randomUUID().toString())  // jti — unique per token
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    // -------------------------------------------------------------------------
    // Token Validation & Parsing
    // -------------------------------------------------------------------------

    public Claims validateAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(validateAndExtractClaims(token).getSubject());
    }

    public String extractRole(String token) {
        return validateAndExtractClaims(token).get("role", String.class);
    }

    public boolean isTokenExpired(String token) {
        try {
            Date expiry = validateAndExtractClaims(token).getExpiration();
            return expiry.before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }

    /**
     * Safe validation — returns false for any JWT exception (expired, tampered, wrong key).
     */
    public boolean isTokenValid(String token) {
        try {
            validateAndExtractClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public Date getExpiration(String token) {
        return validateAndExtractClaims(token).getExpiration();
    }

    // -------------------------------------------------------------------------
    // Key Loading — PEM format
    // -------------------------------------------------------------------------

    private RSAPrivateKey loadPrivateKey(String path) throws Exception {
        String pem = readPemContent(path);
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private RSAPublicKey loadPublicKey(String path) throws Exception {
        String pem = readPemContent(path);
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private String readPemContent(String path) throws Exception {
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Expose public key PEM for other services to download and use for verification.
     */
    public RSAPublicKey getPublicKey() {
        return publicKey;
    }
}
