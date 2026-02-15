-- V1: Create users table
-- PayCore account-service — user identity and credentials

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    phone_number  VARCHAR(20)  UNIQUE,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER'
                  CHECK (role IN ('ADMIN', 'MERCHANT', 'USER')),
    kyc_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'LOCKED')),
    failed_login_attempts INT  NOT NULL DEFAULT 0,
    last_failed_login_at  TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE users IS 'User identity, credentials, and status. Source of truth for auth.';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash (strength=12). Never store plaintext.';
COMMENT ON COLUMN users.failed_login_attempts IS 'Reset to 0 on successful login. Lock account at threshold.';
COMMENT ON COLUMN users.last_failed_login_at IS 'Timestamp of most recent failed attempt, used for lockout window calculation.';
