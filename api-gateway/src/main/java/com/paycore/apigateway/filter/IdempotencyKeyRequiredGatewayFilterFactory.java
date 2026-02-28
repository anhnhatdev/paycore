package com.paycore.apigateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Custom filter requiring Idempotency-Key header on state-modifying requests (POST/PUT).
 * Fails fast with HTTP 400 at Gateway layer if the header is missing or not a valid UUID.
 */
@Component
@Slf4j
public class IdempotencyKeyRequiredGatewayFilterFactory extends AbstractGatewayFilterFactory<IdempotencyKeyRequiredGatewayFilterFactory.Config> {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    public IdempotencyKeyRequiredGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            HttpMethod method = request.getMethod();

            if (method == HttpMethod.POST || method == HttpMethod.PUT) {
                String idempotencyKey = request.getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);

                if (idempotencyKey == null || idempotencyKey.isBlank()) {
                    log.warn("Missing required {} header for {} {}", IDEMPOTENCY_KEY_HEADER, method, request.getURI().getPath());
                    return onError(exchange, "Missing required Idempotency-Key header for transaction request");
                }

                try {
                    UUID.fromString(idempotencyKey.trim());
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid UUID format for {} header: {}", IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    return onError(exchange, "Idempotency-Key header must be a valid UUID format");
                }
            }

            return chain.filter(exchange);
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonError = String.format(
                "{\"statusCode\":400,\"error\":\"Bad Request\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                message, java.time.Instant.now()
        );
        DataBuffer buffer = response.bufferFactory().wrap(jsonError.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Data
    public static class Config {
    }
}
