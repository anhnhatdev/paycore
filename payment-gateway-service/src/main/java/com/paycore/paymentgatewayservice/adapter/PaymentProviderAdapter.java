package com.paycore.paymentgatewayservice.adapter;

import com.paycore.paymentgatewayservice.adapter.dto.*;
import com.paycore.paymentgatewayservice.domain.entity.GatewayTransaction;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;

import java.util.Map;

public interface PaymentProviderAdapter {

    PaymentProvider getProvider();

    InitiateResult initiateDeposit(GatewayDepositRequest request);

    InitiateResult initiateWithdraw(GatewayWithdrawRequest request);

    boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams);

    WebhookResult parseWebhook(byte[] rawBody, Map<String, String> headers, Map<String, String> queryParams);

    ProviderQueryStatusResult queryTransactionStatus(GatewayTransaction tx);
}
