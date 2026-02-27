package com.paycore.apigateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global logging and distributed tracing filter.
 * Injects X-Trace-Id if not present, logs request metadata, and tracks latency.
 */
@Component
@Slf4j
public class GlobalLoggingFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();

        // 1. Trace ID generation or propagation
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String finalTraceId = traceId;
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(TRACE_ID_HEADER, finalTraceId)
                .build();

        String path = request.getURI().getPath();
        String method = request.getMethod().name();
        String clientIp = request.getRemoteAddress() != null 
                ? request.getRemoteAddress().getAddress().getHostAddress() 
                : "unknown";

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .then(Mono.fromRunnable(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                    String userId = mutatedRequest.getHeaders().getFirst("X-User-Id");

                    log.info("GATEWAY_ACCESS | traceId={} | ip={} | method={} | path={} | user={} | status={} | duration={}ms",
                            finalTraceId,
                            clientIp,
                            method,
                            path,
                            userId != null ? userId : "anonymous",
                            statusCode != null ? statusCode.value() : "unknown",
                            duration);
                }));
    }

    @Override
    public int getOrder() {
        // High precedence to ensure trace ID and logging wrap all filters
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
