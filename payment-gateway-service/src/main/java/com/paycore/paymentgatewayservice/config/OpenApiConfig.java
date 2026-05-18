package com.paycore.paymentgatewayservice.config;

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
                        .title("PayCore Payment Gateway Service API")
                        .version("1.0.0")
                        .description("External Payment Gateway Integration Service (VNPay, MoMo, Stripe) for PayCore")
                        .contact(new Contact()
                                .name("PayCore Engineering")
                                .email("anhphanlenhat@gmail.com"))
                        .license(new License().name("Apache 2.0")));
    }
}
