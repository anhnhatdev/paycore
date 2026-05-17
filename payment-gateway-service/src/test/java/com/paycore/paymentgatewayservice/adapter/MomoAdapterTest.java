package com.paycore.paymentgatewayservice.adapter;

import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.InitiateResult;
import com.paycore.paymentgatewayservice.adapter.dto.WebhookResult;
import com.paycore.paymentgatewayservice.adapter.impl.MomoAdapter;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MomoAdapterTest {

    private MomoAdapter momoAdapter;
    private final String secretKey = "MOMO_SECRET_TEST_KEY";

    @BeforeEach
    void setUp() {
        momoAdapter = new MomoAdapter();
        ReflectionTestUtils.setField(momoAdapter, "partnerCode", "MOMO_PAYCORE");
        ReflectionTestUtils.setField(momoAdapter, "accessKey", "MOMO_ACCESS_KEY");
        ReflectionTestUtils.setField(momoAdapter, "secretKey", secretKey);
        ReflectionTestUtils.setField(momoAdapter, "endpoint", "https://test-payment.momo.vn/v2/gateway/api/create");
        ReflectionTestUtils.setField(momoAdapter, "returnUrl", "http://localhost:8080/webhooks/momo/callback");
        ReflectionTestUtils.setField(momoAdapter, "notifyUrl", "http://localhost:8080/webhooks/momo");
    }

    @Test
    @DisplayName("Initiates deposit on MoMo and returns payUrl")
    void initiateDeposit_ReturnsPayUrl() {
        GatewayDepositRequest request = GatewayDepositRequest.builder()
                .internalTransactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .amount(new BigDecimal("200000.00"))
                .currency("VND")
                .provider(PaymentProvider.MOMO)
                .build();

        InitiateResult result = momoAdapter.initiateDeposit(request);

        assertNotNull(result);
        assertNotNull(result.getCheckoutUrl());
        assertTrue(result.getCheckoutUrl().contains("orderId="));
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, result.getStatus());
    }

    @Test
    @DisplayName("Parses MoMo webhook JSON with resultCode 0 as SUCCEEDED")
    void parseWebhook_ResultCodeZero_ReturnsSucceeded() {
        String json = "{\"partnerCode\":\"MOMO_PAYCORE\",\"orderId\":\"MOMO_1234\",\"amount\":200000,\"resultCode\":0,\"message\":\"Successful.\",\"transId\":\"998877\"}";
        byte[] rawBody = json.getBytes(StandardCharsets.UTF_8);

        WebhookResult result = momoAdapter.parseWebhook(rawBody, Map.of(), Map.of());

        assertEquals(GatewayTransactionStatus.SUCCEEDED, result.getStatus());
        assertEquals("MOMO_1234", result.getProviderTransactionRef());
        assertEquals("998877", result.getProviderEventId());
        assertEquals(new BigDecimal("200000"), result.getAmount());
    }
}
