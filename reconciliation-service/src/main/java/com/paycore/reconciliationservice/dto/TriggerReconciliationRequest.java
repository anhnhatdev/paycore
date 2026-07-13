package com.paycore.reconciliationservice.dto;

import com.paycore.reconciliationservice.domain.enums.ReconciliationRunType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerReconciliationRequest {

    @NotNull(message = "runType is required")
    private ReconciliationRunType runType;

    @NotNull(message = "periodStart is required")
    private Instant periodStart;

    @NotNull(message = "periodEnd is required")
    private Instant periodEnd;
}
