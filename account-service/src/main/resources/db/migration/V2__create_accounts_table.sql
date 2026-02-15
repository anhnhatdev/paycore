-- V2: Create accounts table
-- Each user gets exactly one default VND account at registration.
-- Balance is NOT stored here — Ledger Service is the single source of truth for balance.

CREATE TABLE accounts (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    account_number VARCHAR(20)  UNIQUE NOT NULL,
    currency       VARCHAR(3)   NOT NULL DEFAULT 'VND',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE accounts IS 'Wallet accounts linked to users. Balance is owned by Ledger Service only.';
COMMENT ON COLUMN accounts.account_number IS 'Auto-generated. Format: PC + 12 random digits (e.g. PC123456789012).';
COMMENT ON COLUMN accounts.status IS 'FROZEN accounts cannot initiate or receive transactions. CLOSED is terminal.';
