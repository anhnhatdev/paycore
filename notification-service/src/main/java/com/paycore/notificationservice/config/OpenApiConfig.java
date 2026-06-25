package com.paycore.notificationservice.config;

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
                        .title("PayCore Notification Service API")
                        .version("1.0.0")
                        .description("Kafka Event Consumer, User Notification Preferences, and Delivery Audit Logs")
                        .contact(new Contact()
                                .name("PayCore Engineering")
                                .email("anhphanlenhat@gmail.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8086").description("Local Notification Service")
                ));
    }
}
