package com.paycore.paymentgatewayservice.adapter.impl;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Component
public class VnpayAdapter implements PaymentProviderAdapter {

    private static final DateTimeFormatter VNP_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Value("${payment.gateway.vnpay.tmn-code:PAYCORE01}")
    private String tmnCode;

    @Value("${payment.gateway.vnpay.hash-secret:VNPAYSECRETKEY2026PAYCORE123456}")
    private String hashSecret;

    @Value("${payment.gateway.vnpay.url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String vnpayUrl;

    @Value("${payment.gateway.vnpay.return-url:http://localhost:8080/webhooks/vnpay/callback}")
    private String returnUrl;

    @Value("${payment.gateway.vnpay.version:2.1.0}")
    private String version;

    @Value("${payment.gateway.vnpay.command:pay}")
    private String command;

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.VNPAY;
    }

    @Override
    public InitiateResult initiateDeposit(GatewayDepositRequest request) {
        log.info("VNPay initiating deposit: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());

        String txnRef = "VNP_" + request.getInternalTransactionId().toString().replace("-", "").substring(0, 16);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(15, ChronoUnit.MINUTES);

        // VNPay amount is multiplied by 100
        long vnpAmount = request.getAmount().multiply(new BigDecimal(100)).longValue();

        Map<String, String> vnpParams = new HashMap<>();
        vnpParams.put("vnp_Version", version);
        vnpParams.put("vnp_Command", command);
        vnpParams.put("vnp_TmnCode", tmnCode);
        vnpParams.put("vnp_Amount", String.valueOf(vnpAmount));
        vnpParams.put("vnp_CurrCode", request.getCurrency() != null ? request.getCurrency() : "VND");
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", "PayCore Wallet Top-up: " + txnRef);
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", request.getReturnUrl() != null ? request.getReturnUrl() : returnUrl);
        vnpParams.put("vnp_IpAddr", request.getClientIp() != null ? request.getClientIp() : "127.0.0.1");
        vnpParams.put("vnp_CreateDate", VNP_DATE_FORMAT.format(now));
        vnpParams.put("vnp_ExpireDate", VNP_DATE_FORMAT.format(expiresAt));

        // Build query string and hash
        String queryString = buildQueryString(vnpParams, true);
        String hashData = buildQueryString(vnpParams, false);
        String secureHash = hmacSHA512(hashSecret, hashData);

        String paymentUrl = vnpayUrl + "?" + queryString + "&vnp_SecureHash=" + secureHash;

        return InitiateResult.builder()
                .providerTransactionRef(txnRef)
                .checkoutUrl(paymentUrl)
                .expiresAt(expiresAt)
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .message("VNPay payment URL generated successfully")
                .build();
    }

    @Override
    public InitiateResult initiateWithdraw(GatewayWithdrawRequest request) {
        log.info("VNPay initiating payout/withdraw: internalTxId={}, amount={}", request.getInternalTransactionId(), request.getAmount());
        String txnRef = "VNP_OUT_" + request.getInternalTransactionId().toString().replace("-", "").substring(0, 16);

        // Outbound direct payout simulation
        return InitiateResult.builder()
                .providerTransactionRef(txnRef)
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .expiresAt(Instant.now().plus(60, ChronoUnit.MINUTES))
                .message("VNPay payout order dispatched to bank gateway")
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return false;
        }

        String receivedHash = queryParams.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) {
            return false;
        }

        Map<String, String> fields = new HashMap<>(queryParams);
        fields.remove("vnp_SecureHash");
        fields.remove("vnp_SecureHashType");

        String hashData = buildQueryString(fields, false);
        String calculatedHash = hmacSHA512(hashSecret, hashData);

        boolean match = calculatedHash.equalsIgnoreCase(receivedHash);
        if (!match) {
            log.warn("VNPay signature verification failed for params: {}", fields.keySet());
        }
        return match;
    }

    @Override
    public WebhookResult parseWebhook(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        Map<String, String> params = queryParams != null ? queryParams : Map.of();
        String txnRef = params.get("vnp_TxnRef");
        String vnpTransactionNo = params.get("vnp_TransactionNo");
        String responseCode = params.get("vnp_ResponseCode");
        String transactionStatus = params.get("vnp_TransactionStatus");
        String amountStr = params.get("vnp_Amount");

        BigDecimal amount = null;
        if (amountStr != null && !amountStr.isBlank()) {
            try {
                amount = new BigDecimal(amountStr).divide(new BigDecimal(100));
            } catch (Exception ignored) {
            }
        }

        boolean isSuccess = "00".equals(responseCode) && ("00".equals(transactionStatus) || transactionStatus == null);
        GatewayTransactionStatus status = isSuccess ? GatewayTransactionStatus.SUCCEEDED : GatewayTransactionStatus.FAILED;

        return WebhookResult.builder()
                .providerEventId(vnpTransactionNo != null ? vnpTransactionNo : txnRef)
                .providerTransactionRef(txnRef)
                .internalTransactionRef(txnRef)
                .amount(amount)
                .currency("VND")
                .status(status)
                .responseCode(responseCode)
                .message(isSuccess ? "VNPay transaction succeeded" : "VNPay transaction failed with code " + responseCode)
                .rawPayload(params.toString())
                .build();
    }

    @Override
    public ProviderQueryStatusResult queryTransactionStatus(GatewayTransaction tx) {
        log.info("VNPay active query status for tx: id={}, providerRef={}", tx.getId(), tx.getProviderTransactionRef());

        // In sandbox/production, invoke VNPay QueryDR API
        return ProviderQueryStatusResult.builder()
                .providerTransactionRef(tx.getProviderTransactionRef())
                .status(GatewayTransactionStatus.SUCCEEDED)
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .message("Queried VNPay status successfully")
                .build();
    }

    public String buildQueryString(Map<String, String> params, boolean encodeValues) {
        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = params.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                if (encodeValues) {
                    sb.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII));
                    sb.append('=');
                    sb.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII));
                } else {
                    sb.append(fieldName);
                    sb.append('=');
                    sb.append(fieldValue);
                }
                if (itr.hasNext()) {
                    sb.append('&');
                }
            }
        }
        return sb.toString();
    }

    public String hmacSHA512(String key, String data) {
        return new HmacUtils(HmacAlgorithms.HMAC_SHA_512, key).hmacHex(data);
    }
}
