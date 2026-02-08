package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private long expiresIn;     // seconds until access token expiry
    private String tokenType;   // always "Bearer"
}
