ALTER TABLE user_account
    ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE TABLE account_action_token (
    token_id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_account_action_token_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_action_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_action_token_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_account_action_token_account ON account_action_token(account_id, purpose, expires_at);
CREATE UNIQUE INDEX uk_account_action_token_active
    ON account_action_token(account_id, purpose) WHERE consumed_at IS NULL;

CREATE TABLE account_mfa_secret (
    account_id UUID PRIMARY KEY REFERENCES user_account(account_id) ON DELETE CASCADE,
    encrypted_secret TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    enabled_at TIMESTAMPTZ,
    last_accepted_step BIGINT,
    CONSTRAINT ck_account_mfa_secret_nonempty CHECK (length(btrim(encrypted_secret)) > 0)
);

CREATE TABLE account_mfa_recovery_code (
    account_id UUID NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, code_hash),
    CONSTRAINT ck_account_mfa_recovery_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE account_mfa_challenge (
    challenge_hash CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_account_mfa_challenge_hash CHECK (challenge_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_mfa_challenge_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_account_mfa_challenge_expiry ON account_mfa_challenge(expires_at);