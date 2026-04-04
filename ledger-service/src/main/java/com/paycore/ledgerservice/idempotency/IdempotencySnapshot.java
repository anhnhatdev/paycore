package com.paycore.ledgerservice.idempotency;

import com.paycore.ledgerservice.domain.entity.IdempotencyStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencySnapshot {
    private String responseJson;
    private IdempotencyStatus status;
}
