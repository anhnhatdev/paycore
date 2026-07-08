package com.paycore.reconciliationservice.service;

import com.paycore.reconciliationservice.alert.ReconciliationAlertPublisher;
import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyStatus;
import com.paycore.reconciliationservice.domain.enums.DiscrepancyType;
import com.paycore.reconciliationservice.repository.DiscrepancyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscrepancyService {

    private final DiscrepancyRepository discrepancyRepository;
    private final ReconciliationAlertPublisher alertPublisher;

    @Transactional
    public Discrepancy recordDiscrepancy(
            UUID reconciliationRunId,
            DiscrepancyType type,
            DiscrepancySeverity severity,
            String entityReference,
            String expectedValue,
            String actualValue
    ) {
        Optional<Discrepancy> existingOpen = discrepancyRepository
                .findFirstByDiscrepancyTypeAndEntityReferenceAndStatus(type, entityReference, DiscrepancyStatus.OPEN);

        if (existingOpen.isPresent()) {
            Discrepancy existing = existingOpen.get();
            log.info("DEDUP: Updating existing OPEN discrepancy {} with latest runId={}", existing.getId(), reconciliationRunId);
            existing.setReconciliationRunId(reconciliationRunId);
            existing.setExpectedValue(expectedValue);
            existing.setActualValue(actualValue);
            existing.setSeverity(severity);
            return discrepancyRepository.save(existing);
        }

        Discrepancy newDiscrepancy = Discrepancy.builder()
                .reconciliationRunId(reconciliationRunId)
                .discrepancyType(type)
                .severity(severity)
                .entityReference(entityReference)
                .expectedValue(expectedValue)
                .actualValue(actualValue)
                .status(DiscrepancyStatus.OPEN)
                .createdAt(Instant.now())
                .build();

        Discrepancy saved = discrepancyRepository.save(newDiscrepancy);
        log.warn("New discrepancy recorded: id={}, type={}, severity={}, entity={}",
                saved.getId(), type, severity, entityReference);

        alertPublisher.publishAlert(saved);
        return saved;
    }
}
