package com.paycore.transactionservice.dto.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCheckResponse {

    private boolean approved;
    private String riskLevel; // LOW, MEDIUM, HIGH
    private String reason;
}
