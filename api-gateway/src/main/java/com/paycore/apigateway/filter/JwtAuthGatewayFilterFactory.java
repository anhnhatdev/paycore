package com.paycore.apigateway.filter;

import com.paycore.apigateway.security.JwtValidator;
import io.jsonwebtoken.Claims;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Custom GatewayFilterFactory for JWT Authentication.
 * <p>
 * Responsibilities:
 * 1. Skips whitelisted endpoints (login, register, refresh, actuator, docs).
 * 2. Extracts and validates RS256 Bearer token from Authorization header.
 * 3. Fails fast with HTTP 401 if invalid, expired, or missing.
 * 4. Injects X-User-Id, X-User-Role, and X-Kyc-Status headers into downstream request.
 */
@Component
@Slf4j
public class JwtAuthGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    private final JwtValidator jwtValidator;

    private static final List<String> PUBLIC_ENDPOINTS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs",
            "/swagger-ui"
    );

    public JwtAuthGatewayFilterFactory(JwtValidator jwtValidator) {
        super(Config.class);
        this.jwtValidator = jwtValidator;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();

            // 1. Bypass public endpoints
            if (isPublicEndpoint(path)) {
                return chain.filter(exchange);
            }

            // 2. Check Authorization header
            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Missing or invalid Authorization header for path={}", path);
                return onError(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Bearer token");
            }

            String token = authHeader.substring(7);

            // 3. Validate token signature & claims
            if (!jwtValidator.isTokenValid(token)) {
                log.warn("Invalid or expired JWT token for path={}", path);
                return onError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            }

            // 4. Extract claims and forward headers downstream
            Claims claims = jwtValidator.validateAndExtractClaims(token);
            String userId = claims.getSubject();
            String role = claims.get("role", String.class);
            String kycStatus = claims.get("kycStatus", String.class);

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Id", userId != null ? userId : "")
                    .header("X-User-Role", role != null ? role : "")
                    .header("X-Kyc-Status", kycStatus != null ? kycStatus : "")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    private boolean isPublicEndpoint(String path) {
        return PUBLIC_ENDPOINTS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonError = String.format(
                "{\"statusCode\":%d,\"error\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                status.value(), status.getReasonPhrase(), message, java.time.Instant.now()
        );
        DataBuffer buffer = response.bufferFactory().wrap(jsonError.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Data
    public static class Config {
        // Optional configuration parameters if needed in routing DSL
    }
}
