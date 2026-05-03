-- V1__init_gateway_schema.sql
-- Flyway migration for PayCore payment-gateway-service

CREATE TABLE IF NOT EXISTS gateway_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    internal_transaction_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_transaction_ref VARCHAR(255),
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    checkout_url TEXT,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_gateway_tx_internal_id ON gateway_transactions(internal_transaction_id);
CREATE INDEX IF NOT EXISTS idx_gateway_tx_provider_ref ON gateway_transactions(provider, provider_transaction_ref);
CREATE INDEX IF NOT EXISTS idx_gateway_tx_status_updated ON gateway_transactions(status, updated_at);

-- Webhook events audit trail
CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(255),
    raw_payload JSONB NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    gateway_transaction_id UUID,
    received_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_webhook_dedup ON webhook_events(provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_webhook_gateway_tx_id ON webhook_events(gateway_transaction_id);

-- Transactional Outbox Pattern
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events(published) WHERE published = false;
