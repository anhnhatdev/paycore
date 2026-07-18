CREATE TABLE audit_records (
    id UUID DEFAULT gen_random_uuid(),
    sequence_number BIGSERIAL,
    event_id UUID NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(255),
    entity_type VARCHAR(50),
    entity_id VARCHAR(255),
    payload JSONB NOT NULL,
    record_hash VARCHAR(64) NOT NULL,
    prev_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE TABLE audit_records_default PARTITION OF audit_records DEFAULT;

CREATE INDEX idx_audit_entity ON audit_records(entity_type, entity_id, occurred_at);
CREATE INDEX idx_audit_actor ON audit_records(actor_id, occurred_at);
CREATE INDEX idx_audit_event_type ON audit_records(event_type, occurred_at);
CREATE INDEX idx_audit_seq ON audit_records(sequence_number);
CREATE UNIQUE INDEX idx_audit_event_id ON audit_records(event_id, recorded_at);

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE audit_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    accessed_by VARCHAR(255) NOT NULL,
    query_params JSONB NOT NULL,
    result_count INT,
    accessed_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE hash_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    up_to_sequence_number BIGINT NOT NULL,
    checkpoint_hash VARCHAR(64) NOT NULL,
    published_reference TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
