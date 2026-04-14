package com.paycore.transactionservice.dto;

import com.paycore.transactionservice.domain.enums.SagaStepName;
import com.paycore.transactionservice.domain.enums.SagaStepStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaLogResponse {

    private UUID id;
    private SagaStepName stepName;
    private SagaStepStatus status;
    private String requestPayload;
    private String responsePayload;
    private String errorMessage;
    private Instant createdAt;
}
