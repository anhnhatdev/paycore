package com.paycore.auditservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paycore.auditservice.domain.enums.ActorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEventEnvelope {
    private UUID eventId;
    private String sourceService;
    private String eventType;
    private ActorType actorType;
    private String actorId;
    private String entityType;
    private String entityId;
    private Map<String, Object> payload;
    private String rawPayloadJson;
    private Instant occurredAt;
}
