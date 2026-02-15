package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountResponse {
    private UUID id;
    private String accountNumber;
    private String currency;
    private String status;
    private LocalDateTime createdAt;
    // NOTE: balance is NOT here — it lives exclusively in ledger-service
}
