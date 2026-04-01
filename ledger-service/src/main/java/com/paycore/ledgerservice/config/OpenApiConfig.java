package com.paycore.ledgerservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore Ledger Service API")
                        .description("Internal double-entry bookkeeping, balance projection, and reconciliation APIs")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("PayCore Engineering")
                                .email("anhphanlenhat@gmail.com")));
    }
}
