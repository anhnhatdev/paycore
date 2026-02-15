-- V4: Create indexes for query performance

-- users: lookup by email (login), phone (uniqueness check)
CREATE INDEX idx_users_email        ON users(email);
CREATE INDEX idx_users_phone_number ON users(phone_number) WHERE phone_number IS NOT NULL;
CREATE INDEX idx_users_status       ON users(status);

-- accounts: lookup by user_id (list my accounts), account_number (transaction routing)
CREATE INDEX idx_accounts_user_id       ON accounts(user_id);
CREATE INDEX idx_accounts_account_number ON accounts(account_number);

-- refresh_tokens: lookup by user_id (revoke all for a user), token_hash (validate on refresh)
CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);

-- Partial index: active (non-revoked) tokens per user — for fast valid-token checks
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens(user_id, expires_at)
    WHERE revoked = false;
