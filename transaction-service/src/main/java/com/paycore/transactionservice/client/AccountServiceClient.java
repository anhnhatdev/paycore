package com.paycore.transactionservice.client;

import com.paycore.transactionservice.dto.client.AccountResolutionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "account-service", url = "${transaction.services.account-service-url:http://account-service}")
public interface AccountServiceClient {

    @GetMapping("/internal/v1/accounts/number/{accountNumber}")
    AccountResolutionResponse getAccountByNumber(@PathVariable("accountNumber") String accountNumber);

    @GetMapping("/internal/v1/accounts/user/{userId}/default")
    AccountResolutionResponse getDefaultAccountByUserId(@PathVariable("userId") UUID userId);
}
