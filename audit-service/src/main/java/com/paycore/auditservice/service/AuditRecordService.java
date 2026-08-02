package com.paycore.auditservice.service;

import com.paycore.auditservice.domain.entity.AuditRecord;
import com.paycore.auditservice.dto.AuditEventEnvelope;

import java.util.Optional;

public interface AuditRecordService {

    /**
     * Records a new audit event append-only into the cryptographic hash chain.
     */
    Optional<AuditRecord> recordAuditEvent(AuditEventEnvelope event);
}
