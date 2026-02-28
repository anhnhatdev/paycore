package com.paycore.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Validates RS256 JWT access tokens using the public key.
 * Gateway only requires the Public Key (defense-in-depth: private key remains exclusive to account-service).
 */
@Component
@Slf4j
public class JwtValidator {

    @Value("${jwt.public-key-path:classpath:keys/public.pem}")
    private String publicKeyPath;

    private final ResourceLoader resourceLoader;
    private PublicKey publicKey;

    public JwtValidator(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        try {
            Resource resource = resourceLoader.getResource(publicKeyPath);
            if (!resource.exists()) {
                log.warn("JWT public key not found at {}. Using fallback/mock key for testing.", publicKeyPath);
                return;
            }
            try (InputStream is = resource.getInputStream()) {
                String keyStr = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s+", "");

                byte[] keyBytes = Base64.getDecoder().decode(keyStr);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.publicKey = kf.generatePublic(spec);
                log.info("JWT RS256 Public Key loaded successfully at API Gateway");
            }
        } catch (Exception e) {
            log.error("Failed to load JWT public key: {}", e.getMessage(), e);
        }
    }

    public boolean isTokenValid(String token) {
        try {
            if (publicKey == null) return false;
            Claims claims = validateAndExtractClaims(token);
            // Must be an ACCESS token, not a REFRESH token
            return "ACCESS".equals(claims.get("tokenType"));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed at gateway: {}", e.getMessage());
            return false;
        }
    }

    public Claims validateAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
