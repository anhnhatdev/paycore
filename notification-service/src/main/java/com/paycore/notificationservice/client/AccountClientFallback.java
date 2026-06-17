package com.paycore.notificationservice.client;

import com.paycore.notificationservice.dto.UserContactDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class AccountClientFallback implements AccountClient {

    @Override
    public UserContactDto getUserContact(UUID userId) {
        log.warn("AccountClient fallback triggered for userId: {}", userId);
        return UserContactDto.builder()
                .userId(userId)
                .email("user-" + userId.toString().substring(0, 8) + "@paycore.local")
                .phoneNumber("0900000000")
                .fullName("PayCore User")
                .build();
    }
}
