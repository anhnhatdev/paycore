package com.paycore.transactionservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.transactionservice.domain.entity.SagaLog;
import com.paycore.transactionservice.domain.enums.SagaStepName;
import com.paycore.transactionservice.domain.enums.SagaStepStatus;
import com.paycore.transactionservice.repository.SagaLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaLogService {

    private final SagaLogRepository sagaLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStep(UUID transactionId, SagaStepName stepName, SagaStepStatus status,
                           Object requestPayload, Object responsePayload, String errorMessage) {
        try {
            SagaLog logEntry = SagaLog.builder()
                    .transactionId(transactionId)
                    .stepName(stepName)
                    .status(status)
                    .requestPayload(requestPayload != null ? objectMapper.writeValueAsString(requestPayload) : null)
                    .responsePayload(responsePayload != null ? objectMapper.writeValueAsString(responsePayload) : null)
                    .errorMessage(errorMessage)
                    .build();

            sagaLogRepository.save(logEntry);
            log.debug("Recorded saga log: tx={}, step={}, status={}", transactionId, stepName, status);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize saga log payload for tx {}", transactionId, e);
        }
    }
}
