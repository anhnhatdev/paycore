-- =========================================================================
-- PayCore Ledger Service Database Schema (PostgreSQL)
-- Single Source of Truth for Account Balances & Double-Entry Journal
-- =========================================================================

-- 1. Ledger Entries (IMMUTABLE, APPEND-ONLY)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL,
    account_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    balance_after NUMERIC(18, 2) NOT NULL,
    reversal_of_entry_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_entries_reversal ON ledger_entries(reversal_of_entry_id);

-- 2. Balances (Projection / Balance state with optimistic locking support)
CREATE TABLE balances (
    account_id UUID PRIMARY KEY,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    available_balance NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    pending_balance NUMERIC(18, 2) NOT NULL DEFAULT 0.00,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Idempotency Keys (2-phase idempotency state store)
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    response_snapshot TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_status ON idempotency_keys(status, updated_at);

-- 4. Transactional Outbox Events (Reliable Kafka dispatch)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(published, created_at) WHERE published = FALSE;

-- 5. System Accounts (Suspense / External counterparties for deposit and withdrawal)
CREATE TABLE system_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255)
);

-- Seed System Suspense Accounts
INSERT INTO system_accounts (id, code, currency, description) VALUES
('00000000-0000-0000-0000-000000000001', 'SUSPENSE_VND', 'VND', 'System suspense account for external VND gateway deposit and withdrawal'),
('00000000-0000-0000-0000-000000000002', 'SUSPENSE_USD', 'USD', 'System suspense account for external USD gateway deposit and withdrawal');

-- Initialize system account balances
INSERT INTO balances (account_id, currency, available_balance, pending_balance, version, updated_at) VALUES
('00000000-0000-0000-0000-000000000001', 'VND', 0.00, 0.00, 0, CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000002', 'USD', 0.00, 0.00, 0, CURRENT_TIMESTAMP);
