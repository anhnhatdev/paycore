package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegisterResponse {
    private UUID userId;
    private String email;
    private String kycStatus;
    private String message;
}
