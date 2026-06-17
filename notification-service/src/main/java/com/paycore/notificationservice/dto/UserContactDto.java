package com.paycore.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContactDto {
    private UUID userId;
    private String email;
    private String phoneNumber;
    private String fullName;
}
