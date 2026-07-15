package com.paycore.reconciliationservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveDiscrepancyRequest {

    @NotBlank(message = "resolutionNote is required")
    private String resolutionNote;

    private String resolvedBy;

    private boolean isFalsePositive;
}
