-- ==========================================================
-- PayCore Fraud Service - Database Initialization Migration
-- ==========================================================

CREATE TABLE IF NOT EXISTS fraud_rules (
    id UUID PRIMARY KEY,
    rule_code VARCHAR(50) UNIQUE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    params TEXT NOT NULL,
    applies_to_kyc_status VARCHAR(20),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS blacklist_entries (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL,
    entity_value VARCHAR(255) NOT NULL,
    reason VARCHAR(255),
    added_by VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_blacklist_lookup 
    ON blacklist_entries(entity_type, entity_value);

CREATE TABLE IF NOT EXISTS fraud_check_logs (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    decision VARCHAR(10) NOT NULL,
    reason_codes VARCHAR(1000) NOT NULL,
    rules_evaluated TEXT,
    latency_ms INT NOT NULL,
    reviewer_id UUID,
    review_decision VARCHAR(10),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fraud_logs_transaction_id ON fraud_check_logs(transaction_id);
CREATE INDEX IF NOT EXISTS idx_fraud_logs_decision ON fraud_check_logs(decision, review_decision);

-- Initial Fraud Rules Seed
INSERT INTO fraud_rules (id, rule_code, enabled, params, applies_to_kyc_status, updated_at)
VALUES 
    ('11111111-1111-1111-1111-111111111111', 'MAX_AMOUNT_PER_TX_UNVERIFIED', true, '{"maxAmount": 5000000.00, "currency": "VND"}', 'PENDING', CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222', 'MAX_AMOUNT_PER_TX_VERIFIED', true, '{"maxAmount": 50000000.00, "currency": "VND"}', 'VERIFIED', CURRENT_TIMESTAMP),
    ('33333333-3333-3333-3333-333333333333', 'VELOCITY_PER_MINUTE', true, '{"limit": 5, "windowSeconds": 60}', NULL, CURRENT_TIMESTAMP),
    ('44444444-4444-4444-4444-444444444444', 'VELOCITY_PER_HOUR', true, '{"limit": 20, "windowSeconds": 3600}', NULL, CURRENT_TIMESTAMP),
    ('55555555-5555-5555-5555-555555555555', 'VELOCITY_PER_DAY', true, '{"limit": 50, "windowSeconds": 86400}', NULL, CURRENT_TIMESTAMP),
    ('66666666-6666-6666-6666-666666666666', 'LARGE_AMOUNT_REVIEW', true, '{"threshold": 30000000.00, "currency": "VND"}', NULL, CURRENT_TIMESTAMP)
ON CONFLICT (rule_code) DO NOTHING;
