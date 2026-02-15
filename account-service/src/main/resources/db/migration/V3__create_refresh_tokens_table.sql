-- V3: Create refresh_tokens table
-- Refresh token rotation strategy: each refresh issues a new token pair and revokes the old one.
-- Storing token_hash (SHA-256), not the raw token — DB leak does not expose usable tokens.

CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT false,
    device_info VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE refresh_tokens IS 'Hashed refresh tokens with rotation support. Raw token never stored.';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of the raw JWT refresh token string.';
COMMENT ON COLUMN refresh_tokens.device_info IS 'Optional User-Agent or device fingerprint for multi-device logout support.';
COMMENT ON COLUMN refresh_tokens.revoked IS 'Set to true on logout, refresh rotation, or suspicious reuse detection.';
