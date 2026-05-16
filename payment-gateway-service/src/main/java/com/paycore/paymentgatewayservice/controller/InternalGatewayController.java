package com.paycore.paymentgatewayservice.controller;

import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.GatewayWithdrawRequest;
import com.paycore.paymentgatewayservice.dto.DepositInitiateResponse;
import com.paycore.paymentgatewayservice.dto.GatewayTransactionResponse;
import com.paycore.paymentgatewayservice.dto.WithdrawInitiateResponse;
import com.paycore.paymentgatewayservice.service.PaymentGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/v1/gateway")
@RequiredArgsConstructor
@Tag(name = "Internal Payment Gateway APIs", description = "Internal mTLS APIs called by transaction-service")
public class InternalGatewayController {

    private final PaymentGatewayService paymentGatewayService;

    @PostMapping("/deposit/initiate")
    @Operation(summary = "Initiate external gateway deposit", description = "Creates payment intent with external provider and returns redirect URL")
    public ResponseEntity<DepositInitiateResponse> initiateDeposit(@Valid @RequestBody GatewayDepositRequest request) {
        log.info("REST: Initiate deposit: internalTxId={}, provider={}", request.getInternalTransactionId(), request.getProvider());
        DepositInitiateResponse response = paymentGatewayService.initiateDeposit(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/withdraw/initiate")
    @Operation(summary = "Initiate external gateway payout/withdrawal", description = "Dispatches payout request to external banking/gateway provider")
    public ResponseEntity<WithdrawInitiateResponse> initiateWithdraw(@Valid @RequestBody GatewayWithdrawRequest request) {
        log.info("REST: Initiate withdraw: internalTxId={}, provider={}", request.getInternalTransactionId(), request.getProvider());
        WithdrawInitiateResponse response = paymentGatewayService.initiateWithdraw(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions/{id}/status")
    @Operation(summary = "Get transaction status by gateway ID", description = "Queries current gateway transaction state")
    public ResponseEntity<GatewayTransactionResponse> getTransactionStatus(@PathVariable("id") UUID id) {
        GatewayTransactionResponse response = paymentGatewayService.getTransactionStatus(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transactions/internal/{internalTxId}/status")
    @Operation(summary = "Get transaction status by internal transaction ID", description = "Queries current gateway transaction state using internal transaction ID")
    public ResponseEntity<GatewayTransactionResponse> getTransactionByInternalId(@PathVariable("internalTxId") UUID internalTxId) {
        GatewayTransactionResponse response = paymentGatewayService.getTransactionByInternalId(internalTxId);
        return ResponseEntity.ok(response);
    }
}
