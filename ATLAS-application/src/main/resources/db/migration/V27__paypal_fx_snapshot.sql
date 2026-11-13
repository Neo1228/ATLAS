ALTER TABLE payment_attempt
    ADD COLUMN provider_amount_minor BIGINT,
    ADD COLUMN provider_currency VARCHAR(3),
    ADD COLUMN fx_rate_won_per_usd NUMERIC(20, 10),
    ADD COLUMN fx_rate_source VARCHAR(64),
    ADD COLUMN fx_rate_as_of DATE,
    ADD CONSTRAINT payment_attempt_fx_snapshot_check CHECK (
        (provider_amount_minor IS NULL AND provider_currency IS NULL
            AND fx_rate_won_per_usd IS NULL AND fx_rate_source IS NULL AND fx_rate_as_of IS NULL)
        OR (provider_amount_minor > 0 AND provider_currency IS NOT NULL
            AND fx_rate_won_per_usd > 0 AND fx_rate_source IS NOT NULL AND fx_rate_as_of IS NOT NULL)
    );

ALTER TABLE payment_refund_operation
    ADD COLUMN provider_amount_minor BIGINT,
    ADD COLUMN provider_currency VARCHAR(3),
    ADD CONSTRAINT payment_refund_provider_amount_check CHECK (
        (provider_amount_minor IS NULL AND provider_currency IS NULL)
        OR (provider_amount_minor > 0 AND provider_currency IS NOT NULL)
    );
