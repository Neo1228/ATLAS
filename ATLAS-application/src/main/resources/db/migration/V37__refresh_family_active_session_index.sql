CREATE INDEX ix_refresh_family_active_account_created
    ON refresh_token_family (account_id, created_at DESC, family_id DESC)
    INCLUDE (expires_at)
    WHERE revoked_at IS NULL;
