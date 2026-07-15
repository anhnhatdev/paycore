package com.paycore.reconciliationservice.alert;

import com.paycore.reconciliationservice.domain.entity.Discrepancy;
import com.paycore.reconciliationservice.domain.enums.DiscrepancySeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReconciliationAlertPublisher {

    public void publishAlert(Discrepancy discrepancy) {
        if (discrepancy == null) return;

        DiscrepancySeverity severity = discrepancy.getSeverity();
        String type = discrepancy.getDiscrepancyType().name();
        String entityRef = discrepancy.getEntityReference();
        String expected = discrepancy.getExpectedValue();
        String actual = discrepancy.getActualValue();

        switch (severity) {
            case LOW:
                log.info("[RECONCILIATION-ALERT: LOW] Type={}, EntityRef={}, Expected={}, Actual={}",
                        type, entityRef, expected, actual);
                break;

            case MEDIUM:
                log.warn("[RECONCILIATION-ALERT: MEDIUM] Discrepancy logged for shift review: Type={}, EntityRef={}, Expected={}, Actual={}",
                        type, entityRef, expected, actual);
                break;

            case HIGH:
                log.error("[RECONCILIATION-ALERT: HIGH] URGENT OPS ALERT! Immediate investigation required: Type={}, EntityRef={}, Expected={}, Actual={}",
                        type, entityRef, expected, actual);
                break;

            case CRITICAL:
                log.error("================================================================================");
                log.error("🚨🚨🚨 [RECONCILIATION-ALERT: CRITICAL] SYSTEM INTEGRITY VIOLATION DETECTED! 🚨🚨🚨");
                log.error("Type: {}", type);
                log.error("Entity Reference: {}", entityRef);
                log.error("Expected Value: {}", expected);
                log.error("Actual Value: {}", actual);
                log.error("Action Required: PAGE ON-CALL IMMEDIATELY — DOUBLE ENTRY INVARIANT OR FINANCIAL MISMATCH");
                log.error("================================================================================");
                break;
        }
    }
}
