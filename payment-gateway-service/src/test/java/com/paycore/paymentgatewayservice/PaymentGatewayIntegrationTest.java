package com.paycore.paymentgatewayservice;

import com.paycore.paymentgatewayservice.adapter.PaymentProviderAdapter;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderFactory;
import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.GatewayWithdrawRequest;
import com.paycore.paymentgatewayservice.adapter.dto.InitiateResult;
import com.paycore.paymentgatewayservice.adapter.dto.WebhookResult;
import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.entity.OutboxEvent;
import com.paycore.paymentgatewayservice.domain.entity.WebhookEvent;
import com.paycore.paymentgatewayservice.domain.enums.GatewayDirection;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import com.paycore.paymentgatewayservice.domain.enums.WebhookProcessingStatus;
import com.paycore.paymentgatewayservice.dto.DepositInitiateResponse;
import com.paycore.paymentgatewayservice.dto.WebhookIngestResponse;
import com.paycore.paymentgatewayservice.dto.WithdrawInitiateResponse;
import com.paycore.paymentgatewayservice.reconciliation.GatewayReconciliationJob;
import com.paycore.paymentgatewayservice.repository.GatewayTransactionRepository;
import com.paycore.paymentgatewayservice.repository.OutboxEventRepository;
import com.paycore.paymentgatewayservice.repository.WebhookEventRepository;
import com.paycore.paymentgatewayservice.service.PaymentGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class PaymentGatewayIntegrationTest {

    @Autowired
    private PaymentGatewayService paymentGatewayService;

    @Autowired
    private GatewayTransactionRepository transactionRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private GatewayReconciliationJob reconciliationJob;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        webhookEventRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    @Test
    @DisplayName("Initiate deposit creates PENDING_PROVIDER transaction and returns checkout URL")
    void initiateDeposit_Success_ReturnsCheckoutUrl() {
        UUID internalTxId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        GatewayDepositRequest request = GatewayDepositRequest.builder()
                .internalTransactionId(internalTxId)
                .idempotencyKey(idempotencyKey)
                .amount(new BigDecimal("500000.00"))
                .currency("VND")
                .provider(PaymentProvider.VNPAY)
                .returnUrl("https://paycore.app/callback")
                .build();

        DepositInitiateResponse response = paymentGatewayService.initiateDeposit(request);

        assertNotNull(response.getGatewayTransactionId());
        assertEquals(internalTxId, response.getInternalTransactionId());
        assertEquals(PaymentProvider.VNPAY, response.getProvider());
        assertNotNull(response.getCheckoutUrl());
        assertNotNull(response.getExpiresAt());
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, response.getStatus());

        GatewayTransaction saved = transactionRepository.findById(response.getGatewayTransactionId()).orElseThrow();
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, saved.getStatus());
        assertEquals(new BigDecimal("500000.00"), saved.getAmount());
    }

    @Test
    @DisplayName("Initiate deposit is idempotent: subsequent call with same idempotency key returns identical record")
    void initiateDeposit_Idempotent_ReusesExistingTransaction() {
        UUID internalTxId = UUID.randomUUID();
        String idempotencyKey = "IDEMP_" + UUID.randomUUID();

        GatewayDepositRequest request = GatewayDepositRequest.builder()
                .internalTransactionId(internalTxId)
                .idempotencyKey(idempotencyKey)
                .amount(new BigDecimal("250000.00"))
                .currency("VND")
                .provider(PaymentProvider.VNPAY)
                .build();

        DepositInitiateResponse first = paymentGatewayService.initiateDeposit(request);
        DepositInitiateResponse second = paymentGatewayService.initiateDeposit(request);

        assertEquals(first.getGatewayTransactionId(), second.getGatewayTransactionId());
        assertEquals(first.getCheckoutUrl(), second.getCheckoutUrl());
        assertEquals(1, transactionRepository.count());
    }

    @Test
    @DisplayName("Initiate withdraw creates OUTBOUND transaction in PENDING_PROVIDER status")
    void initiateWithdraw_Success() {
        UUID internalTxId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        GatewayWithdrawRequest request = GatewayWithdrawRequest.builder()
                .internalTransactionId(internalTxId)
                .idempotencyKey(idempotencyKey)
                .amount(new BigDecimal("1000000.00"))
                .currency("VND")
                .provider(PaymentProvider.VNPAY)
                .bankCode("VCB")
                .bankAccountNumber("9988776655")
                .bankAccountName("NGUYEN VAN A")
                .build();

        WithdrawInitiateResponse response = paymentGatewayService.initiateWithdraw(request);

        assertNotNull(response.getGatewayTransactionId());
        assertEquals(PaymentProvider.VNPAY, response.getProvider());
        assertEquals(GatewayTransactionStatus.PENDING_PROVIDER, response.getStatus());

        GatewayTransaction saved = transactionRepository.findById(response.getGatewayTransactionId()).orElseThrow();
        assertEquals(GatewayDirection.OUTBOUND, saved.getDirection());
    }

    @Test
    @DisplayName("Webhook with valid signature marks transaction SUCCEEDED and creates Outbox event")
    void handleWebhook_ValidSignature_SucceedsTransaction() {
        // Seed an active pending transaction
        String providerRef = "VNP_TEST_SUCCESS_01";
        GatewayTransaction tx = GatewayTransaction.builder()
                .internalTransactionId(UUID.randomUUID())
                .provider(PaymentProvider.VNPAY)
                .providerTransactionRef(providerRef)
                .direction(GatewayDirection.INBOUND)
                .amount(new BigDecimal("300000.00"))
                .currency("VND")
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        tx = transactionRepository.saveAndFlush(tx);

        // Prepare valid VNPay query params
        Map<String, String> queryParams = Map.of(
                "vnp_TxnRef", providerRef,
                "vnp_TransactionNo", "EVENT_001",
                "vnp_ResponseCode", "00",
                "vnp_TransactionStatus", "00",
                "vnp_Amount", "30000000",
                "vnp_SecureHash", "MOCK_VALID_HASH"
        );

        // Process webhook using Momo/Vnpay
        WebhookIngestResponse response = paymentGatewayService.processWebhook(
                PaymentProvider.VNPAY,
                null,
                Map.of(),
                // For testing adapter signature with mock or precomputed hash
                prepareVnpayParams(providerRef, "00", "30000000", "EVENT_001")
        );

        assertEquals("PROCESSED", response.getStatus());

        // Verify transaction updated to SUCCEEDED
        GatewayTransaction updatedTx = transactionRepository.findById(tx.getId()).orElseThrow();
        assertEquals(GatewayTransactionStatus.SUCCEEDED, updatedTx.getStatus());

        // Verify webhook event recorded
        List<WebhookEvent> webhooks = webhookEventRepository.findAll();
        assertEquals(1, webhooks.size());
        assertTrue(webhooks.get(0).isSignatureValid());
        assertEquals(WebhookProcessingStatus.PROCESSED, webhooks.get(0).getProcessingStatus());

        // Verify Outbox event created
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertEquals(1, outboxEvents.size());
        assertEquals("GatewayPaymentSucceeded", outboxEvents.get(0).getEventType());
    }

    @Test
    @DisplayName("Webhook with invalid signature is rejected, records audit event, but returns 200 OK")
    void handleWebhook_InvalidSignature_Returns200AndRejects() {
        Map<String, String> invalidParams = Map.of(
                "vnp_TxnRef", "VNP_FAKE_REF",
                "vnp_ResponseCode", "00",
                "vnp_SecureHash", "BAD_SIGNATURE"
        );

        WebhookIngestResponse response = paymentGatewayService.processWebhook(
                PaymentProvider.VNPAY,
                null,
                Map.of(),
                invalidParams
        );

        assertEquals("REJECTED_INVALID_SIGNATURE", response.getStatus());

        List<WebhookEvent> webhooks = webhookEventRepository.findAll();
        assertEquals(1, webhooks.size());
        assertFalse(webhooks.get(0).isSignatureValid());
        assertEquals(WebhookProcessingStatus.REJECTED_INVALID_SIGNATURE, webhooks.get(0).getProcessingStatus());
        assertEquals(0, outboxEventRepository.count());
    }

    @Test
    @DisplayName("Duplicate webhook event is recorded as IGNORED_DUPLICATE and does not create duplicate outbox events")
    void handleWebhook_DuplicateEvent_IgnoredCleanly() {
        String providerRef = "VNP_DUP_REF_01";
        GatewayTransaction tx = GatewayTransaction.builder()
                .internalTransactionId(UUID.randomUUID())
                .provider(PaymentProvider.VNPAY)
                .providerTransactionRef(providerRef)
                .direction(GatewayDirection.INBOUND)
                .amount(new BigDecimal("100000.00"))
                .currency("VND")
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        transactionRepository.saveAndFlush(tx);

        Map<String, String> params = prepareVnpayParams(providerRef, "00", "10000000", "DUP_EVENT_123");

        // First call -> PROCESSED
        WebhookIngestResponse first = paymentGatewayService.processWebhook(PaymentProvider.VNPAY, null, Map.of(), params);
        assertEquals("PROCESSED", first.getStatus());

        // Second call with same eventId -> IGNORED_DUPLICATE
        WebhookIngestResponse second = paymentGatewayService.processWebhook(PaymentProvider.VNPAY, null, Map.of(), params);
        assertEquals("IGNORED_DUPLICATE", second.getStatus());

        // Only 1 outbox event produced
        assertEquals(1, outboxEventRepository.count());
    }

    @Test
    @DisplayName("Webhook with unmapped transaction records audit and returns 200 without crashing")
    void handleWebhook_UnmappedTransaction_Returns200Cleanly() {
        Map<String, String> params = prepareVnpayParams("UNKNOWN_REF_999", "00", "10000000", "UNMAPPED_EVENT_1");

        WebhookIngestResponse response = paymentGatewayService.processWebhook(PaymentProvider.VNPAY, null, Map.of(), params);

        assertEquals("UNMAPPED_TRANSACTION", response.getStatus());
        assertEquals(1, webhookEventRepository.count());
        assertEquals(0, outboxEventRepository.count());
    }

    @Test
    @DisplayName("Reconciliation job queries provider, recovers pending transaction to SUCCEEDED and emits outbox event")
    void reconciliationJob_RecoversPendingTransaction() {
        GatewayTransaction pendingTx = GatewayTransaction.builder()
                .internalTransactionId(UUID.randomUUID())
                .provider(PaymentProvider.VNPAY)
                .providerTransactionRef("VNP_RECONCILE_01")
                .direction(GatewayDirection.INBOUND)
                .amount(new BigDecimal("750000.00"))
                .currency("VND")
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .idempotencyKey(UUID.randomUUID().toString())
                .createdAt(Instant.now().minus(10, ChronoUnit.MINUTES))
                .updatedAt(Instant.now().minus(6, ChronoUnit.MINUTES))
                .build();
        pendingTx = transactionRepository.saveAndFlush(pendingTx);

        // Run reconciliation
        reconciliationJob.reconcilePendingTransactions();

        GatewayTransaction recovered = transactionRepository.findById(pendingTx.getId()).orElseThrow();
        assertEquals(GatewayTransactionStatus.SUCCEEDED, recovered.getStatus());

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertEquals(1, outboxEvents.size());
        assertEquals("GatewayPaymentSucceeded", outboxEvents.get(0).getEventType());
        assertTrue(outboxEvents.get(0).getPayload().contains("RECONCILE"));
    }

    @Test
    @DisplayName("Reconciliation expiration job expires transactions past expiresAt and emits GatewayPaymentExpired")
    void reconciliationJob_ExpiresStaleTransaction() {
        GatewayTransaction expiredTx = GatewayTransaction.builder()
                .internalTransactionId(UUID.randomUUID())
                .provider(PaymentProvider.VNPAY)
                .providerTransactionRef("VNP_EXPIRED_01")
                .direction(GatewayDirection.INBOUND)
                .amount(new BigDecimal("120000.00"))
                .currency("VND")
                .status(GatewayTransactionStatus.PENDING_PROVIDER)
                .idempotencyKey(UUID.randomUUID().toString())
                .createdAt(Instant.now().minus(20, ChronoUnit.MINUTES))
                .updatedAt(Instant.now().minus(20, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().minus(5, ChronoUnit.MINUTES))
                .build();
        expiredTx = transactionRepository.saveAndFlush(expiredTx);

        reconciliationJob.expireStaleTransactions();

        GatewayTransaction result = transactionRepository.findById(expiredTx.getId()).orElseThrow();
        assertEquals(GatewayTransactionStatus.EXPIRED, result.getStatus());

        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        assertEquals(1, outboxEvents.size());
        assertEquals("GatewayPaymentExpired", outboxEvents.get(0).getEventType());
    }

    @Test
    @DisplayName("Sensitive card information in webhook payload is masked before saving to webhook_events")
    void sensitiveCardData_IsMaskedInWebhookAudit() {
        String json = "{\"orderId\":\"MOMO_CARD_01\",\"transId\":\"TRANS_CARD_01\",\"amount\":100000,\"resultCode\":0,\"cardNumber\":\"4111222233334444\",\"cvv\":\"999\"}";
        byte[] rawBody = json.getBytes(StandardCharsets.UTF_8);

        // Even if signature fails or passes, raw payload in DB must be masked
        paymentGatewayService.processWebhook(PaymentProvider.MOMO, rawBody, Map.of(), Map.of());

        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertEquals(1, events.size());
        String savedRaw = events.get(0).getRawPayload();

        assertFalse(savedRaw.contains("4111222233334444"));
        assertFalse(savedRaw.contains("\"999\""));
    }

    private Map<String, String> prepareVnpayParams(String txnRef, String responseCode, String amount, String eventId) {
        Map<String, String> params = new java.util.HashMap<>();
        params.put("vnp_Amount", amount);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_Command", "pay");
        params.put("vnp_OrderInfo", "PayCore Top-up");
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", responseCode);
        params.put("vnp_TmnCode", "PAYCORE01");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_TransactionNo", eventId);

        // Build hash with test secret
        com.paycore.paymentgatewayservice.adapter.impl.VnpayAdapter adapter = new com.paycore.paymentgatewayservice.adapter.impl.VnpayAdapter();
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "hashSecret", "TESTSECRETKEYVNPAY2026");

        String hashData = adapter.buildQueryString(params, false);
        String hash = adapter.hmacSHA512("TESTSECRETKEYVNPAY2026", hashData);
        params.put("vnp_SecureHash", hash);

        return params;
    }
}
