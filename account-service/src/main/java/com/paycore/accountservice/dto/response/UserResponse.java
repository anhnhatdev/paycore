package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    private UUID id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String role;
    private String kycStatus;
    private String status;
    private LocalDateTime createdAt;
    // NOTE: passwordHash is NEVER included in any response DTO
}
