package com.paycore.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * PayCore API Gateway
 * <p>
 * Edge routing service that acts as the single point of entry for all client traffic.
 * Provides JWT RS256 fail-fast authentication, Redis-backed rate limiting,
 * request tracing, and security isolation of internal endpoints.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
