package com.paycore.transactionservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore Transaction Service API")
                        .description("Saga Orchestrator for financial transactions, multi-service workflows, outbox events, and stuck transaction recovery.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("PayCore Engineering")
                                .email("dev@paycore.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://paycore.com")));
    }
}
