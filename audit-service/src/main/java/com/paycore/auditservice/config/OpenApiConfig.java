package com.paycore.auditservice.config;

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
                        .title("PayCore Audit & Compliance Service API")
                        .version("1.0.0")
                        .description("Tamper-Evident Immutable Audit Log, Cryptographic Hash Chaining, and Meta-Audit Logging")
                        .contact(new Contact()
                                .name("PayCore Compliance Engineering")
                                .email("anhphanlenhat@gmail.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8088").description("Local Audit Service")
                ));
    }
}
