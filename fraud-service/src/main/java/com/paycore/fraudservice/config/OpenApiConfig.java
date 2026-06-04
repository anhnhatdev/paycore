package com.paycore.fraudservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore Fraud Service API")
                        .version("1.0.0")
                        .description("Real-time fraud assessment, velocity monitoring, and risk management APIs for PayCore")
                        .contact(new Contact()
                                .name("PayCore Risk & Security")
                                .email("anhphanlenhat@gmail.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
