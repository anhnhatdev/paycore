package com.paycore.reconciliationservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore Reconciliation Service API")
                        .version("1.0.0")
                        .description("Internal and External Financial Reconciliation Engine — Detection and Audit Only")
                        .contact(new Contact()
                                .name("PayCore Engineering")
                                .email("anhphanlenhat@gmail.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8087").description("Local Reconciliation Service")
                ));
    }
}
