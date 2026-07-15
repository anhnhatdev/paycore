package com.paycore.reconciliationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSettlementRequest {

    @NotBlank(message = "provider is required")
    private String provider;

    @NotNull(message = "reportDate is required")
    private LocalDate reportDate;

    @NotBlank(message = "csvContent is required")
    private String csvContent;
}
