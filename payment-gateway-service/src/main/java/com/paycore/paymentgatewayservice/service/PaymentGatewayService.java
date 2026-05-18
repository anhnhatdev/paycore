package com.paycore.paymentgatewayservice.service;

import com.paycore.paymentgatewayservice.adapter.dto.GatewayDepositRequest;
import com.paycore.paymentgatewayservice.adapter.dto.GatewayWithdrawRequest;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import com.paycore.paymentgatewayservice.dto.DepositInitiateResponse;
import com.paycore.paymentgatewayservice.dto.GatewayTransactionResponse;
import com.paycore.paymentgatewayservice.dto.WebhookIngestResponse;
import com.paycore.paymentgatewayservice.dto.WithdrawInitiateResponse;

import java.util.Map;
import java.util.UUID;

public interface PaymentGatewayService {

    DepositInitiateResponse initiateDeposit(GatewayDepositRequest request);

    WithdrawInitiateResponse initiateWithdraw(GatewayWithdrawRequest request);

    WebhookIngestResponse processWebhook(PaymentProvider provider, byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams);

    GatewayTransactionResponse getTransactionStatus(UUID gatewayTxId);

    GatewayTransactionResponse getTransactionByInternalId(UUID internalTxId);
}
