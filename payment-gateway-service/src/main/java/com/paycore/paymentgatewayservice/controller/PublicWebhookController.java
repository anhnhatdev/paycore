package com.paycore.paymentgatewayservice.controller;

import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import com.paycore.paymentgatewayservice.dto.WebhookIngestResponse;
import com.paycore.paymentgatewayservice.service.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
@Tag(name = "Public Webhook Ingress", description = "Public Webhook endpoints invoked by external payment gateways")
public class PublicWebhookController {

    private final PaymentGatewayService paymentGatewayService;

    @PostMapping("/{provider}")
    @Operation(summary = "Ingest provider webhook notification", description = "Verifies signature, records audit event, and initiates asynchronous event dispatch")
    public ResponseEntity<WebhookIngestResponse> handleWebhook(
            @PathVariable("provider") String providerStr,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader Map<String, String> headers,
            @RequestParam(required = false) Map<String, String> queryParams
    ) {
        PaymentProvider provider;
        try {
            provider = PaymentProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid payment provider in webhook path: {}", providerStr);
            return ResponseEntity.ok(WebhookIngestResponse.builder()
                    .status("REJECTED_UNKNOWN_PROVIDER")
                    .message("Unknown payment provider: " + providerStr)
                    .build());
        }

        log.info("Public webhook received for provider={}", provider);
        WebhookIngestResponse response = paymentGatewayService.processWebhook(provider, rawBody, headers, queryParams);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{provider}/callback")
    @Operation(summary = "Ingest provider redirect callback", description = "Handles browser redirect callback from gateways like VNPay / MoMo")
    public ResponseEntity<WebhookIngestResponse> handleRedirectCallback(
            @PathVariable("provider") String providerStr,
            @RequestParam Map<String, String> queryParams,
            @RequestHeader Map<String, String> headers
    ) {
        PaymentProvider provider;
        try {
            provider = PaymentProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(WebhookIngestResponse.builder()
                    .status("REJECTED_UNKNOWN_PROVIDER")
                    .message("Unknown payment provider: " + providerStr)
                    .build());
        }

        log.info("Public redirect callback received for provider={}, paramCount={}", provider, queryParams.size());
        WebhookIngestResponse response = paymentGatewayService.processWebhook(provider, null, headers, queryParams);
        return ResponseEntity.ok(response);
    }
}
