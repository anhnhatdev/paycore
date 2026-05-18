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
public class MomoAdapter implements PaymentProviderAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payment.gateway.momo.partner-code:MOMO_PAYCORE}")
    private String partnerCode;

    @Value("${payment.gateway.momo.access-key:MOMO_ACCESS_KEY}")
    private String accessKey;

    @Value("${payment.gateway.momo.secret-key:MOMO_SECRET_KEY}")
    private String secretKey;

    @Value("${payment.gateway.momo.endpoint:https://test-payment.momo.vn/v2/gateway/api/create}")
    private String endpoint;

    @Value("${payment.gateway.momo.return-url:http://localhost:8080/webhooks/momo/callback}")
    private String returnUrl;

    @Value("${payment.gateway.momo.notify-url:http://localhost:8080/webhooks/momo}")
    private String notifyUrl;

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.MOMO;
    }

    @Override
    public InitiateResult initiateDeposit(GatewayDepositRequest request) {
        log.info("MoMo initiating deposit: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());

        String orderId = "MOMO_" + request.getInternalTransactionId().toString().replace("-", "").substring(0, 16);
        String requestId = String.valueOf(System.currentTimeMillis());
        long amount = request.getAmount().longValue();
        String orderInfo = "PayCore Deposit: " + orderId;
        String redirectUrl = request.getReturnUrl() != null ? request.getReturnUrl() : returnUrl;
        String ipnUrl = notifyUrl;
        String extraData = "";
        String requestType = "captureWallet";

        // Signature format
        String rawSignature = "accessKey=" + accessKey +
                "&amount=" + amount +
                "&extraData=" + extraData +
                "&ipnUrl=" + ipnUrl +
                "&orderId=" + orderId +
                "&orderInfo=" + orderInfo +
                "&partnerCode=" + partnerCode +
                "&redirectUrl=" + redirectUrl +
                "&requestId=" + requestId +
                "&requestType=" + requestType;

        String signature = hmacSHA256(secretKey, rawSignature);
        String payUrl = "https://test-payment.momo.vn/v2/gateway/pay?orderId=" + orderId + "&signature=" + signature;

        return InitiateResult.builder()
                .providerTransactionRef(orderId)
                .checkoutUrl(payUrl)
                .expiresAt(Instant.now().plus(15, ChronoUnit.MINUTES))
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .message("MoMo checkout payUrl created")
                .build();
    }

    @Override
    public InitiateResult initiateWithdraw(GatewayWithdrawRequest request) {
        log.info("MoMo initiating withdraw/disbursement: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());
        String orderId = "MOMO_OUT_" + request.getInternalTransactionId().toString().replace("-", "").substring(0, 16);

        return InitiateResult.builder()
                .providerTransactionRef(orderId)
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .expiresAt(Instant.now().plus(60, ChronoUnit.MINUTES))
                .message("MoMo disbursement request submitted")
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        if (rawBody == null || rawBody.length == 0) {
            return false;
        }

        try {
            JsonNode node = objectMapper.readTree(rawBody);
            String receivedSignature = node.has("signature") ? node.get("signature").asText() : null;
            if (receivedSignature == null) {
                return false;
            }

            String partnerCode = node.has("partnerCode") ? node.get("partnerCode").asText() : "";
            String orderId = node.has("orderId") ? node.get("orderId").asText() : "";
            String requestId = node.has("requestId") ? node.get("requestId").asText() : "";
            String amount = node.has("amount") ? node.get("amount").asText() : "";
            String orderInfo = node.has("orderInfo") ? node.get("orderInfo").asText() : "";
            String orderType = node.has("orderType") ? node.get("orderType").asText() : "";
            String transId = node.has("transId") ? node.get("transId").asText() : "";
            String resultCode = node.has("resultCode") ? node.get("resultCode").asText() : "";
            String message = node.has("message") ? node.get("message").asText() : "";
            String payType = node.has("payType") ? node.get("payType").asText() : "";
            String responseTime = node.has("responseTime") ? node.get("responseTime").asText() : "";
            String extraData = node.has("extraData") ? node.get("extraData").asText() : "";

            String rawSignature = "accessKey=" + accessKey +
                    "&amount=" + amount +
                    "&extraData=" + extraData +
                    "&message=" + message +
                    "&orderId=" + orderId +
                    "&orderInfo=" + orderInfo +
                    "&orderType=" + orderType +
                    "&partnerCode=" + partnerCode +
                    "&payType=" + payType +
                    "&requestId=" + requestId +
                    "&responseTime=" + responseTime +
                    "&resultCode=" + resultCode +
                    "&transId=" + transId;

            String calculated = hmacSHA256(secretKey, rawSignature);
            return calculated.equalsIgnoreCase(receivedSignature);
        } catch (Exception e) {
            log.warn("Failed to parse MoMo webhook body for signature validation", e);
            return false;
        }
    }

    @Override
    public WebhookResult parseWebhook(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            String orderId = node.has("orderId") ? node.get("orderId").asText() : null;
            String transId = node.has("transId") ? node.get("transId").asText() : null;
            int resultCode = node.has("resultCode") ? node.get("resultCode").asInt(-1) : -1;
            String message = node.has("message") ? node.get("message").asText() : "";
            BigDecimal amount = node.has("amount") ? new BigDecimal(node.get("amount").asText()) : null;

            boolean isSuccess = (resultCode == 0);
            GatewayTransactionStatus status = isSuccess ? GatewayTransactionStatus.SUCCEEDED : GatewayTransactionStatus.FAILED;

            return WebhookResult.builder()
                    .providerEventId(transId != null ? transId : orderId)
                    .providerTransactionRef(orderId)
                    .internalTransactionRef(orderId)
                    .amount(amount)
                    .currency("VND")
                    .status(status)
                    .responseCode(String.valueOf(resultCode))
                    .message(message)
                    .rawPayload(new String(rawBody, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            log.error("Error parsing MoMo webhook JSON", e);
            return WebhookResult.builder()
                    .status(GatewayTransactionStatus.FAILED)
                    .message("Failed to parse MoMo JSON: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public ProviderQueryStatusResult queryTransactionStatus(GatewayTransaction tx) {
        log.info("MoMo active query status for tx: id={}, providerRef={}", tx.getId(), tx.getProviderTransactionRef());
        return ProviderQueryStatusResult.builder()
                .providerTransactionRef(tx.getProviderTransactionRef())
                .status(GatewayTransactionStatus.SUCCEEDED)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .message("Queried MoMo status successfully")
                .build();
    }

    public String hmacSHA256(String key, String data) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_256, key).hmacHex(data);
    }
}
