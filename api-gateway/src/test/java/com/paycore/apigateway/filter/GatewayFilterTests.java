package com.paycore.apigateway.filter;

import com.paycore.apigateway.security.JwtValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayFilterTests {

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private GatewayFilterChain filterChain;

    @Test
    @DisplayName("DenyPublicAccessFilter should block /internal/** with 403 Forbidden")
    void denyPublicAccess_ShouldReturn403() {
        DenyPublicAccessGatewayFilterFactory factory = new DenyPublicAccessGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new DenyPublicAccessGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/internal/v1/accounts/status").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("JwtAuthFilter should bypass public auth endpoint /api/v1/auth/login without token")
    void jwtAuthFilter_PublicEndpoint_BypassesFilter() {
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        JwtAuthGatewayFilterFactory factory = new JwtAuthGatewayFilterFactory(jwtValidator);
        GatewayFilter filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verify(filterChain, times(1)).filter(any());
        verifyNoInteractions(jwtValidator);
    }

    @Test
    @DisplayName("JwtAuthFilter should reject protected route /api/v1/accounts/me without token with 401")
    void jwtAuthFilter_MissingToken_Returns401() {
        JwtAuthGatewayFilterFactory factory = new JwtAuthGatewayFilterFactory(jwtValidator);
        GatewayFilter filter = factory.apply(new JwtAuthGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("IdempotencyKeyRequiredFilter should return 400 when Idempotency-Key header is missing on POST")
    void idempotencyFilter_MissingHeader_Returns400() {
        IdempotencyKeyRequiredGatewayFilterFactory factory = new IdempotencyKeyRequiredGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new IdempotencyKeyRequiredGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/transactions/transfer").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("IdempotencyKeyRequiredFilter should return 400 when Idempotency-Key is not a valid UUID")
    void idempotencyFilter_InvalidUUID_Returns400() {
        IdempotencyKeyRequiredGatewayFilterFactory factory = new IdempotencyKeyRequiredGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new IdempotencyKeyRequiredGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/transactions/transfer")
                .header(IdempotencyKeyRequiredGatewayFilterFactory.IDEMPOTENCY_KEY_HEADER, "invalid-not-a-uuid")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("IdempotencyKeyRequiredFilter should pass when valid UUID Idempotency-Key is provided")
    void idempotencyFilter_ValidUUID_Passes() {
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        IdempotencyKeyRequiredGatewayFilterFactory factory = new IdempotencyKeyRequiredGatewayFilterFactory();
        GatewayFilter filter = factory.apply(new IdempotencyKeyRequiredGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/transactions/transfer")
                .header(IdempotencyKeyRequiredGatewayFilterFactory.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verify(filterChain, times(1)).filter(any());
    }
}
