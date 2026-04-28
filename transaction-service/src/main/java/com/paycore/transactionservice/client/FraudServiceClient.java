package com.paycore.transactionservice.client;

import com.paycore.transactionservice.dto.client.FraudCheckRequest;
import com.paycore.transactionservice.dto.client.FraudCheckResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "fraud-service", url = "${transaction.services.fraud-service-url:http://fraud-service}")
public interface FraudServiceClient {

    @PostMapping("/internal/v1/fraud/evaluate")
    FraudCheckResponse evaluateRisk(@RequestBody FraudCheckRequest request);
}
