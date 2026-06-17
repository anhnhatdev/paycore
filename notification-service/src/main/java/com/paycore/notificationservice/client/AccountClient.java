package com.paycore.notificationservice.client;

import com.paycore.notificationservice.dto.UserContactDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(
        name = "account-service",
        url = "${notification.account-service-url:http://localhost:8081}",
        fallback = AccountClientFallback.class
)
public interface AccountClient {

    @GetMapping("/internal/v1/users/{userId}/contact")
    UserContactDto getUserContact(@PathVariable("userId") UUID userId);
}
