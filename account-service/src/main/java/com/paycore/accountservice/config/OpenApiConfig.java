package com.paycore.accountservice.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "JWT access token obtained from POST /api/v1/auth/login"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore — Account Service API")
                        .description("""
                                Authentication, user management, and account lifecycle management.
                                
                                **Notes:**
                                - Internal endpoints (`/internal/**`) are NOT documented here — they are mTLS-protected service-to-service APIs.
                                - Balance information is NOT available in this service. Query transaction-service for balances.
                                - All write operations require `Idempotency-Key` header where applicable.
                                """)
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("PayCore Team")
                                .email("dev@paycore.com"))
                        .license(new License().name("Private")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development"),
                        new Server().url("http://api-gateway:8080").description("Via API Gateway")
                ));
    }
}
