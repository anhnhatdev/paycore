package com.paycore.fraudservice.dto;

import com.paycore.fraudservice.domain.enums.FraudDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCheckResponse {
    private FraudDecision decision;
    private List<String> reasonCodes;
    private UUID checkId;
}
