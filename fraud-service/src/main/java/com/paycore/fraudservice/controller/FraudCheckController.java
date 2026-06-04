package com.paycore.fraudservice.controller;

import com.paycore.fraudservice.domain.entity.FraudCheckLog;
import com.paycore.fraudservice.domain.enums.ReviewDecision;
import com.paycore.fraudservice.dto.FraudCheckRequest;
import com.paycore.fraudservice.dto.FraudCheckResponse;
import com.paycore.fraudservice.service.FraudService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/v1/fraud")
@RequiredArgsConstructor
@Tag(name = "Fraud Check & Review APIs", description = "Internal mTLS risk evaluation and manual review queue endpoints")
public class FraudCheckController {

    private final FraudService fraudService;

    @PostMapping("/check")
    @Operation(summary = "Real-time fraud risk evaluation", description = "Evaluates transaction against blacklist, velocity, and dynamic rule engine")
    public ResponseEntity<FraudCheckResponse> checkRisk(@Valid @RequestBody FraudCheckRequest request) {
        log.info("REST: Fraud risk check requested for txId: {}, amount: {} {}",
                request.getTransactionId(), request.getAmount(), request.getCurrency());
        FraudCheckResponse response = fraudService.evaluateTransaction(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/review-queue")
    @Operation(summary = "Get pending review queue", description = "Retrieves all transactions marked as REVIEW awaiting manual admin resolution")
    public ResponseEntity<List<FraudCheckLog>> getReviewQueue() {
        return ResponseEntity.ok(fraudService.getReviewQueue());
    }

    @PostMapping("/review-queue/{checkId}/decide")
    @Operation(summary = "Decide manual review", description = "Admin submits APPROVE or REJECT decision on a flagged transaction")
    public ResponseEntity<FraudCheckLog> decideReview(
            @PathVariable("checkId") UUID checkId,
            @RequestBody ReviewDecisionRequest request
    ) {
        FraudCheckLog updatedLog = fraudService.decideReview(
                checkId,
                request.getReviewerId(),
                request.getDecision(),
                request.getNotes()
        );
        return ResponseEntity.ok(updatedLog);
    }

    @Data
    public static class ReviewDecisionRequest {
        private UUID reviewerId;
        private ReviewDecision decision;
        private String notes;
    }
}
