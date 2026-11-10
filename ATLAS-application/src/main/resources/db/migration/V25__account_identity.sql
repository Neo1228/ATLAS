CREATE TABLE buyer (
    buyer_id UUID PRIMARY KEY
);

CREATE TABLE user_account (
    account_id UUID PRIMARY KEY DEFAULT uuidv7(),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_account_password_hash CHECK (length(btrim(password_hash)) > 0),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE UNIQUE INDEX uk_user_account_email_ci ON user_account (lower(email));

CREATE TABLE buyer_account (
    account_id UUID PRIMARY KEY REFERENCES user_account(account_id) ON DELETE CASCADE,
    buyer_id UUID NOT NULL UNIQUE REFERENCES buyer(buyer_id) ON DELETE CASCADE
);

-- Existing Seller rows remain unclaimed; only authenticated onboarding creates a membership.
CREATE TABLE seller_account (
    account_id UUID NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    seller_id UUID NOT NULL UNIQUE REFERENCES seller(seller_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, seller_id)
);

CREATE TABLE refresh_token_family (
    family_id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL REFERENCES user_account(account_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_refresh_family_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_refresh_family_account ON refresh_token_family(account_id, expires_at);

CREATE TABLE account_refresh_token (
    token_id UUID PRIMARY KEY DEFAULT uuidv7(),
    family_id UUID NOT NULL REFERENCES refresh_token_family(family_id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_refresh_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_refresh_token_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_account_refresh_token_family ON account_refresh_token(family_id, expires_at);
