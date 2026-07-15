package com.paycore.reconciliationservice.client;

import com.paycore.reconciliationservice.dto.TransactionSummaryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class TransactionClientFallback implements TransactionClient {

    @Override
    public List<TransactionSummaryDto> getCompletedTransactions(Instant periodStart, Instant periodEnd) {
        log.warn("TransactionClient fallback triggered for getCompletedTransactions");
        return Collections.emptyList();
    }

    @Override
    public TransactionSummaryDto getTransactionById(UUID id) {
        log.warn("TransactionClient fallback triggered for getTransactionById: {}", id);
        return null;
    }
}
