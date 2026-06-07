package com.paycore.fraudservice.service;

import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.dto.FraudCheckResponse;
import com.paycore.fraudservice.domain.entity.FraudCheckLog;
import com.paycore.fraudservice.domain.enums.ReviewDecision;

import java.util.List;
import java.util.UUID;

public interface FraudService {

    /**
     * Synchronously evaluates risk for a transaction with latency budget control.
     */
    FraudCheckResponse evaluateTransaction(FraudCheckRequest request);

    /**
     * Retrieves all pending manual review transactions.
     */
    List<FraudCheckLog> getReviewQueue();

    /**
     * Admin manual review decision.
     */
    FraudCheckLog decideReview(UUID checkId, UUID reviewerId, ReviewDecision decision, String notes);
}
