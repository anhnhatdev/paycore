package com.paycore.paymentgatewayservice.adapter;

import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.InitiateResult;
import com.paycore.paymentgatewayservice.adapter.dto.WebhookResult;
import com.paycore.paymentgatewayservice.adapter.impl.StripeAdapter;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StripeAdapterTest {

    private StripeAdapter stripeAdapter;
    private final String webhookSecret = "whsec_test_secret_2026";

    @BeforeEach
    void setUp() {
        stripeAdapter = new StripeAdapter();
        ReflectionTestUtils.setField(stripeAdapter, "apiKey", "sk_test_mock");
        ReflectionTestUtils.setField(stripeAdapter, "webhookSecret", webhookSecret);
    }

    @Test
    @DisplayName("Initiates deposit on Stripe and returns checkout session URL")
    void initiateDeposit_ReturnsCheckoutUrl() {
        GatewayDepositRequest request = GatewayDepositRequest.builder()
                .internalTransactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .provider(PaymentProvider.STRIPE)
                .build();

        InitiateResult result = stripeAdapter.initiateDeposit(request);

        assertNotNull(result);
        assertTrue(result.getCheckoutUrl().contains("https://checkout.stripe.com/c/pay/cs_test_"));
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, result.getStatus());
    }

    @Test
    @DisplayName("Verifies authentic Stripe-Signature header")
    void verifyWebhookSignature_ValidHeader_ReturnsTrue() {
        String payload = "{\"id\":\"evt_123\",\"type\":\"payment_intent.succeeded\"}";
        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        String payloadToSign = timestamp + "." + payload;
        String signature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, webhookSecret).hmacHex(payloadToSign);
        String stripeSignatureHeader = "t=" + timestamp + ",v1=" + signature;

        boolean isValid = stripeAdapter.verifyWebhookSignature(rawBody, Map.of("Stripe-Signature", stripeSignatureHeader), Map.of());
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Parses payment_intent.succeeded event as SUCCEEDED")
    void parseWebhook_PaymentIntentSucceeded_ReturnsSucceeded() {
        String payload = "{\"id\":\"evt_001\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_123\",\"amount\":5000,\"currency\":\"usd\"}}}";
        byte[] rawBody = payload.getBytes(StandardCharsets.UTF_8);

        WebhookResult result = stripeAdapter.parseWebhook(rawBody, Map.of(), Map.of());

        assertEquals(GatewayTransactionStatus.SUCCEEDED, result.getStatus());
        assertEquals("evt_001", result.getProviderEventId());
        assertEquals("pi_123", result.getProviderTransactionRef());
        assertEquals(new BigDecimal("50.00"), result.getAmount().setScale(2));
        assertEquals("USD", result.getCurrency());
    }
}
