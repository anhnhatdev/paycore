package com.paycore.paymentgatewayservice.adapter;

import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.InitiateResult;
import com.paycore.paymentgatewayservice.adapter.dto.WebhookResult;
import com.paycore.paymentgatewayservice.adapter.impl.VnpayAdapter;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VnpayAdapterTest {

    private VnpayAdapter vnpayAdapter;
    private final String secretKey = "TESTSECRETKEY2026";

    @BeforeEach
    void setUp() {
        vnpayAdapter = new VnpayAdapter();
        ReflectionTestUtils.setField(vnpayAdapter, "tmnCode", "PAYCORE01");
        ReflectionTestUtils.setField(vnpayAdapter, "hashSecret", secretKey);
        ReflectionTestUtils.setField(vnpayAdapter, "vnpayUrl", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
        ReflectionTestUtils.setField(vnpayAdapter, "returnUrl", "http://localhost:8080/webhooks/vnpay/callback");
        ReflectionTestUtils.setField(vnpayAdapter, "version", "2.1.0");
        ReflectionTestUtils.setField(vnpayAdapter, "command", "pay");
    }

    @Test
    @DisplayName("Initiates deposit and generates valid VNPay checkout URL")
    void initiateDeposit_GeneratesValidUrl() {
        GatewayDepositRequest request = GatewayDepositRequest.builder()
                .internalTransactionId(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .provider(PaymentProvider.VNPAY)
                .clientIp("127.0.0.1")
                .build();

        InitiateResult result = vnpayAdapter.initiateDeposit(request);

        assertNotNull(result);
        assertNotNull(result.getCheckoutUrl());
        assertTrue(result.getCheckoutUrl().contains("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        assertTrue(result.getCheckoutUrl().contains("vnp_Amount=15000000"));
        assertTrue(result.getCheckoutUrl().contains("vnp_SecureHash="));
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, result.getStatus());
    }

    @Test
    @DisplayName("Verifies valid VNPay webhook signature")
    void verifyWebhookSignature_ValidSignature_ReturnsTrue() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_Command", "pay");
        params.put("vnp_OrderInfo", "PayCore Top-up");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "PAYCORE01");
        params.put("vnp_TxnRef", "VNP_123456");

        String hashData = vnpayAdapter.buildQueryString(params, false);
        String secureHash = vnpayAdapter.hmacSHA512(secretKey, hashData);
        params.put("vnp_SecureHash", secureHash);

        boolean isValid = vnpayAdapter.verifyWebhookSignature(null, Map.of(), params);
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Rejects tampered VNPay webhook signature")
    void verifyWebhookSignature_TamperedParams_ReturnsFalse() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Amount", "10000000");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TxnRef", "VNP_123456");
        params.put("vnp_SecureHash", "INVALID_HASH_123");

        boolean isValid = vnpayAdapter.verifyWebhookSignature(null, Map.of(), params);
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Parses VNPay response code 00 as SUCCEEDED")
    void parseWebhook_SuccessCode_ReturnsSucceeded() {
        Map<String, String> params = Map.of(
                "vnp_TxnRef", "VNP_REF_999",
                "vnp_TransactionNo", "14567890",
                "vnp_ResponseCode", "00",
                "vnp_TransactionStatus", "00",
                "vnp_Amount", "50000000"
        );

        WebhookResult result = vnpayAdapter.parseWebhook(null, Map.of(), params);

        assertEquals(GatewayTransactionStatus.SUCCEEDED, result.getStatus());
        assertEquals("VNP_REF_999", result.getProviderTransactionRef());
        assertEquals("14567890", result.getProviderEventId());
        assertEquals(new BigDecimal("500000.00"), result.getAmount().setScale(2));
    }
}
