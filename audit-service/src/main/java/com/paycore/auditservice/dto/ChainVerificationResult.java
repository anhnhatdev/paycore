package com.paycore.auditservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainVerificationResult {
    private boolean valid;
    private Long verifiedRecordsCount;
    private Long corruptedSequenceNumber;
    private String expectedHash;
    private String actualHash;
    private String message;
}
