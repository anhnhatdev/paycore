package com.paycore.eurekaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * PayCore Eureka Discovery Server
 * <p>
 * Service Registry where all microservices (api-gateway, account-service, ledger-service,
 * transaction-service, etc.) register themselves dynamically to avoid hardcoded host/port addresses.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
