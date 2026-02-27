package com.paycore.apigateway.filter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Custom Gateway filter to strictly block any public access to internal endpoints.
 * All /internal/** paths are intended solely for service-to-service communication via mTLS.
 */
@Component
@Slf4j
public class DenyPublicAccessGatewayFilterFactory extends AbstractGatewayFilterFactory<DenyPublicAccessGatewayFilterFactory.Config> {

    public DenyPublicAccessGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();
            log.warn("Blocked public attempt to access internal path: {}", path);

            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.FORBIDDEN);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

            String jsonError = String.format(
                    "{\"statusCode\":403,\"error\":\"Forbidden\",\"message\":\"Access to internal endpoints is forbidden from public gateway.\",\"timestamp\":\"%s\"}",
                    java.time.Instant.now()
            );
            DataBuffer buffer = response.bufferFactory().wrap(jsonError.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        };
    }

    @Data
    public static class Config {
    }
}
