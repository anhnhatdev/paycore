package com.paycore.paymentgatewayservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderAdapter;
import com.paycore.paymentgatewayservice.adapter.PaymentProviderFactory;
import com.paycore.paymentgatewayservice.adapter.dto.*;
import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.entity.OutboxEvent;
import com.paycore.paymentgatewayservice.domain.entity.WebhookEvent;
import com.paycore.paymentgatewayservice.domain.enums.GatewayDirection;
import com.paycore.paymentgatewayservice.domain.enums.GatewayTransactionStatus;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import com.paycore.paymentgatewayservice.domain.enums.WebhookProcessingStatus;
import com.paycore.paymentgatewayservice.dto.DepositInitiateResponse;
import com.paycore.paymentgatewayservice.dto.GatewayTransactionResponse;
import com.paycore.paymentgatewayservice.dto.WebhookIngestResponse;
import com.paycore.paymentgatewayservice.dto.WithdrawInitiateResponse;
import com.paycore.paymentgatewayservice.repository.GatewayTransactionRepository;
import com.paycore.paymentgatewayservice.repository.OutboxEventRepository;
import com.paycore.paymentgatewayservice.repository.WebhookEventRepository;
import com.paycore.paymentgatewayservice.service.PaymentGatewayService;
import com.paycore.paymentgatewayservice.util.SensitiveDataMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayServiceImpl implements PaymentGatewayService {

    private final GatewayTransactionRepository transactionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DepositInitiateResponse initiateDeposit(GatewayDepositRequest request) {
        log.info("Initiating Deposit on Payment Gateway: internalTxId={}, provider={}, amount={} {}",
                request.getInternalTransactionId(), request.getProvider(), request.getAmount(), request.getCurrency());

        // 1. Idempotency Check
        Optional<GatewayTransaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent deposit request matched existing tx: id={}", existing.get().getId());
            return mapToDepositResponse(existing.get());
        }

        // 2. Create initial record in INITIATED
        GatewayTransaction transaction = GatewayTransaction.builder()
                .internalTransactionId(request.getInternalTransactionId())
                .provider(request.getProvider())
                .direction(GatewayDirection.INBOUND)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(GatewayTransactionStatus.INITIATED)
                .idempotencyKey(request.getIdempotencyKey())
                .build();

        transaction = transactionRepository.saveAndFlush(transaction);

        // 3. Call Provider Adapter
        PaymentProviderAdapter adapter = providerFactory.getAdapter(request.getProvider());
        InitiateResult result = adapter.initiateDeposit(request);

        // 4. Update transaction with checkout URL and ref
        transaction.setProviderTransactionRef(result.getProviderTransactionRef());
        transaction.setCheckoutUrl(result.getCheckoutUrl());
        transaction.setExpiresAt(result.getExpiresAt());
        transaction.setStatus(result.getStatus() != null ? result.getStatus() : GatewayTransactionStatus.PENDING_PROVIDER);
        transaction = transactionRepository.saveAndFlush(transaction);

        return mapToDepositResponse(transaction);
    }

    @Override
    @Transactional
    public WithdrawInitiateResponse initiateWithdraw(GatewayWithdrawRequest request) {
        log.info("Initiating Withdrawal on Payment Gateway: internalTxId={}, provider={}, amount={} {}",
                request.getInternalTransactionId(), request.getProvider(), request.getAmount(), request.getCurrency());

        // 1. Idempotency Check
        Optional<GatewayTransaction> existing = transactionRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent withdraw request matched existing tx: id={}", existing.get().getId());
            return mapToWithdrawResponse(existing.get());
        }

        // 2. Create initial record in INITIATED
        GatewayTransaction transaction = GatewayTransaction.builder()
                .internalTransactionId(request.getInternalTransactionId())
                .provider(request.getProvider())
                .direction(GatewayDirection.OUTBOUND)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(GatewayTransactionStatus.INITIATED)
                .idempotencyKey(request.getIdempotencyKey())
                .build();

        transaction = transactionRepository.saveAndFlush(transaction);

        // 3. Call Provider Adapter
        PaymentProviderAdapter adapter = providerFactory.getAdapter(request.getProvider());
        InitiateResult result = adapter.initiateWithdraw(request);

        // 4. Update transaction
        transaction.setProviderTransactionRef(result.getProviderTransactionRef());
        transaction.setExpiresAt(result.getExpiresAt());
        transaction.setStatus(result.getStatus() != null ? result.getStatus() : GatewayTransactionStatus.PENDING_PROVIDER);
        transaction = transactionRepository.saveAndFlush(transaction);

        return mapToWithdrawResponse(transaction);
    }

    @Override
    @Transactional
    public WebhookIngestResponse processWebhook(PaymentProvider provider, byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams) {
        log.info("Received Webhook notification for provider={}", provider);

        PaymentProviderAdapter adapter = providerFactory.getAdapter(provider);

        // Prepare raw payload for storage with PCI-DSS sensitive data masking
        String rawString = (rawBody != null && rawBody.length > 0)
                ? new String(rawBody, StandardCharsets.UTF_8)
                : (queryParams != null ? queryParams.toString() : "{}");
        String maskedPayload = SensitiveDataMasker.mask(rawString);

        // Step 1 & 2: Verify Webhook Signature (MUST be done first)
        boolean signatureValid = adapter.verifyWebhookSignature(rawBody, headers, queryParams);
        if (!signatureValid) {
            log.warn("Webhook signature validation failed for provider={}", provider);
            WebhookEvent failedEvent = WebhookEvent.builder()
                    .provider(provider)
                    .rawPayload(maskedPayload)
                    .signatureValid(false)
                    .processingStatus(WebhookProcessingStatus.REJECTED_INVALID_SIGNATURE)
                    .build();
            webhookEventRepository.save(failedEvent);

            // Return 200 OK so external gateway does not spam retries, but internal logic rejects it
            return WebhookIngestResponse.builder()
                    .status("REJECTED_INVALID_SIGNATURE")
                    .message("Webhook signature validation failed")
                    .build();
        }

        // Step 3: Parse Webhook Payload
        WebhookResult webhookResult = adapter.parseWebhook(rawBody, headers, queryParams);

        // Step 3.1: Check Duplicate Event (Idempotency)
        if (webhookResult.getProviderEventId() != null &&
                webhookEventRepository.existsByProviderAndProviderEventId(provider, webhookResult.getProviderEventId())) {
            log.info("Duplicate webhook event received for provider={}, eventId={}. Ignored.",
                    provider, webhookResult.getProviderEventId());

            WebhookEvent duplicateEvent = WebhookEvent.builder()
                    .provider(provider)
                    .providerEventId(webhookResult.getProviderEventId())
                    .rawPayload(maskedPayload)
                    .signatureValid(true)
                    .processingStatus(WebhookProcessingStatus.IGNORED_DUPLICATE)
                    .build();
            webhookEventRepository.save(duplicateEvent);

            return WebhookIngestResponse.builder()
                    .status("IGNORED_DUPLICATE")
                    .message("Duplicate webhook event ignored")
                    .build();
        }

        // Step 4: Resolve GatewayTransaction
        Optional<GatewayTransaction> txOpt = Optional.empty();
        if (webhookResult.getProviderTransactionRef() != null) {
            txOpt = transactionRepository.findByProviderAndProviderTransactionRef(provider, webhookResult.getProviderTransactionRef());
        }

        if (txOpt.isEmpty() && webhookResult.getInternalTransactionRef() != null) {
            try {
                // In case ref contains UUID
                String ref = webhookResult.getInternalTransactionRef().replace("VNP_", "").replace("MOMO_", "");
                if (ref.length() == 32 || ref.length() == 36) {
                    txOpt = transactionRepository.findByInternalTransactionId(UUID.fromString(ref));
                }
            } catch (Exception ignored) {
            }
        }

        if (txOpt.isEmpty()) {
            log.warn("Webhook received for unknown or unresolvable transaction: provider={}, ref={}",
                    provider, webhookResult.getProviderTransactionRef());

            WebhookEvent unmappedEvent = WebhookEvent.builder()
                    .provider(provider)
                    .providerEventId(webhookResult.getProviderEventId())
                    .rawPayload(maskedPayload)
                    .signatureValid(true)
                    .processingStatus(WebhookProcessingStatus.PROCESSED)
                    .build();
            webhookEventRepository.save(unmappedEvent);

            return WebhookIngestResponse.builder()
                    .status("UNMAPPED_TRANSACTION")
                    .message("Webhook recorded but no matching internal transaction found")
                    .build();
        }

        GatewayTransaction transaction = txOpt.get();

        // Step 5: Update Transaction Status & Emit Outbox Event
        if (webhookResult.getStatus() == GatewayTransactionStatus.SUCCEEDED) {
            transaction.setStatus(GatewayTransactionStatus.SUCCEEDED);
            recordOutboxEvent(transaction.getInternalTransactionId(), "GatewayPaymentSucceeded", Map.of(
                    "gatewayTransactionId", transaction.getId(),
                    "internalTransactionId", transaction.getInternalTransactionId(),
                    "provider", provider.name(),
                    "providerTransactionRef", transaction.getProviderTransactionRef() != null ? transaction.getProviderTransactionRef() : "",
                    "amount", transaction.getAmount(),
                    "currency", transaction.getCurrency(),
                    "status", "SUCCEEDED"
            ));
        } else {
            transaction.setStatus(GatewayTransactionStatus.FAILED);
            recordOutboxEvent(transaction.getInternalTransactionId(), "GatewayPaymentFailed", Map.of(
                    "gatewayTransactionId", transaction.getId(),
                    "internalTransactionId", transaction.getInternalTransactionId(),
                    "provider", provider.name(),
                    "providerTransactionRef", transaction.getProviderTransactionRef() != null ? transaction.getProviderTransactionRef() : "",
                    "amount", transaction.getAmount(),
                    "currency", transaction.getCurrency(),
                    "status", "FAILED",
                    "reason", webhookResult.getMessage() != null ? webhookResult.getMessage() : "Payment rejected by provider"
            ));
        }
        transaction = transactionRepository.saveAndFlush(transaction);

        // Step 6: Record Webhook Audit Log
        WebhookEvent webhookEvent = WebhookEvent.builder()
                .provider(provider)
                .providerEventId(webhookResult.getProviderEventId())
                .rawPayload(maskedPayload)
                .signatureValid(true)
                .processingStatus(WebhookProcessingStatus.PROCESSED)
                .gatewayTransactionId(transaction.getId())
                .processedAt(Instant.now())
                .build();
        webhookEventRepository.save(webhookEvent);

        log.info("Webhook processed successfully: provider={}, txId={}, status={}",
                provider, transaction.getId(), transaction.getStatus());

        return WebhookIngestResponse.builder()
                .status("PROCESSED")
                .message("Webhook processed successfully")
                .gatewayTransactionId(transaction.getId())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GatewayTransactionResponse getTransactionStatus(UUID gatewayTxId) {
        GatewayTransaction tx = transactionRepository.findById(gatewayTxId)
                .orElseThrow(() -> new IllegalArgumentException("Gateway transaction not found: " + gatewayTxId));
        return mapToGatewayResponse(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public GatewayTransactionResponse getTransactionByInternalId(UUID internalTxId) {
        GatewayTransaction tx = transactionRepository.findByInternalTransactionId(internalTxId)
                .orElseThrow(() -> new IllegalArgumentException("Gateway transaction not found for internal ID: " + internalTxId));
        return mapToGatewayResponse(tx);
    }

    private void recordOutboxEvent(UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .published(false)
                    .build();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to serialize outbox payload for aggregateId: {}", aggregateId, e);
        }
    }

    private DepositInitiateResponse mapToDepositResponse(GatewayTransaction tx) {
        return DepositInitiateResponse.builder()
                .gatewayTransactionId(tx.getId())
                .internalTransactionId(tx.getInternalTransactionId())
                .provider(tx.getProvider())
                .providerTransactionRef(tx.getProviderTransactionRef())
                .checkoutUrl(tx.getCheckoutUrl())
                .expiresAt(tx.getExpiresAt())
                .status(tx.getStatus())
                .build();
    }

    private WithdrawInitiateResponse mapToWithdrawResponse(GatewayTransaction tx) {
        return WithdrawInitiateResponse.builder()
                .gatewayTransactionId(tx.getId())
                .internalTransactionId(tx.getInternalTransactionId())
                .provider(tx.getProvider())
                .providerTransactionRef(tx.getProviderTransactionRef())
                .expiresAt(tx.getExpiresAt())
                .status(tx.getStatus())
                .build();
    }

    private GatewayTransactionResponse mapToGatewayResponse(GatewayTransaction tx) {
        return GatewayTransactionResponse.builder()
                .id(tx.getId())
                .internalTransactionId(tx.getInternalTransactionId())
                .provider(tx.getProvider())
                .providerTransactionRef(tx.getProviderTransactionRef())
                .direction(tx.getDirection())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .status(tx.getStatus())
                .checkoutUrl(tx.getCheckoutUrl())
                .createdAt(tx.getCreatedAt())
                .updatedAt(tx.getUpdatedAt())
                .expiresAt(tx.getExpiresAt())
                .build();
    }
}
