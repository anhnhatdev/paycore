package com.paycore.paymentgatewayservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookIngestResponse {
    private String status;
    private String message;
    private UUID gatewayTransactionId;
}
