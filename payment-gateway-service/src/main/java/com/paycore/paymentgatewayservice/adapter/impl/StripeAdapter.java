package com.paycore.paymentgatewayservice.adapter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderAdapter;
import com.paycore.paymentgatewayservice.adapter.dto.*;
import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Component
public class StripeAdapter implements PaymentProviderAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payment.gateway.stripe.api-key:sk_test_paycore_stripe_mock_key_2026}")
    private String apiKey;

    @Value("${payment.gateway.stripe.webhook-secret:whsec_paycore_stripe_secret_2026}")
    private String webhookSecret;

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public InitiateResult initiateDeposit(GatewayDepositRequest request) {
        log.info("Stripe initiating deposit / Checkout Session: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());
        String sessionRef = "cs_test_" + request.getInternalTransactionId().toString().replace("-", "");
        String checkoutUrl = "https://checkout.stripe.com/c/pay/" + sessionRef;

        return InitiateResult.builder()
                .providerTransactionRef(sessionRef)
                .checkoutUrl(checkoutUrl)
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .message("Stripe Checkout Session initialized")
                .build();
    }

    @Override
    public InitiateResult initiateWithdraw(GatewayWithdrawRequest request) {
        log.info("Stripe initiating payout/transfer: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());
        String payoutRef = "po_test_" + request.getInternalTransactionId().toString().replace("-", "");

        return InitiateResult.builder()
                .providerTransactionRef(payoutRef)
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .expiresAt(Instant.now().plus(60, ChronoUnit.MINUTES))
                .message("Stripe Payout scheduled")
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        if (rawBody == null || headers == null) {
            return false;
        }

        String sigHeader = headers.get("stripe-signature");
        if (sigHeader == null) {
            sigHeader = headers.get("Stripe-Signature");
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            return false;
        }

        String timestamp = null;
        String signature = null;

        String[] parts = sigHeader.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                if ("t".equals(kv[0].trim())) {
                    timestamp = kv[1].trim();
                } else if ("v1".equals(kv[0].trim())) {
                    signature = kv[1].trim();
                }
            }
        }

        if (timestamp == null || signature == null) {
            return false;
        }

        String payloadToSign = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
        String calculated = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, webhookSecret).hmacHex(payloadToSign);

        return calculated.equalsIgnoreCase(signature);
    }

    @Override
    public WebhookResult parseWebhook(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String eventId = root.has("id") ? root.get("id").asText() : null;
            String type = root.has("type") ? root.get("type").asText() : "";

            JsonNode dataNode = root.get("data");
            JsonNode objectNode = dataNode != null ? dataNode.get("object") : null;

            String providerTxRef = null;
            BigDecimal amount = null;
            String currency = "USD";

            if (objectNode != null) {
                if (objectNode.has("id")) {
                    providerTxRef = objectNode.get("id").asText();
                }
                if (objectNode.has("amount")) {
                    amount = new BigDecimal(objectNode.get("amount").asText()).divide(new BigDecimal(100));
                }
                if (objectNode.has("currency")) {
                    currency = objectNode.get("currency").asText().toUpperCase();
                }
            }

            boolean isSuccess = "payment_intent.succeeded".equals(type) || "checkout.session.completed".equals(type);
            GatewayTransactionStatus status = isSuccess ? GatewayTransactionStatus.SUCCEEDED : GatewayTransactionStatus.FAILED;

            return WebhookResult.builder()
                    .providerEventId(eventId)
                    .providerTransactionRef(providerTxRef)
                    .internalTransactionRef(providerTxRef)
                    .amount(amount)
                    .currency(currency)
                    .status(status)
                    .responseCode(type)
                    .message("Stripe event: " + type)
                    .rawPayload(new String(rawBody, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            log.error("Error parsing Stripe webhook", e);
            return WebhookResult.builder()
                    .status(GatewayTransactionStatus.FAILED)
                    .message("Failed to parse Stripe JSON: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderQueryStatusResult queryTransactionStatus(GatewayTransaction tx) {
        log.info("Stripe active query status for tx: id={}, providerRef={}", tx.getId(), tx.getProviderTransactionRef());
        return ProviderQueryStatusResult.builder()
                .providerTransactionRef(tx.getProviderTransactionRef())
                .status(GatewayTransactionStatus.SUCCEEDED)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .message("Queried Stripe status successfully")
                .build();
    }
}
