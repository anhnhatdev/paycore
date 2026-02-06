package com.paycore.accountservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtConfig {
    private String privateKeyPath;
    private String publicKeyPath;
    private long accessTokenExpirationMs;
    private long refreshTokenExpirationMs;
}
