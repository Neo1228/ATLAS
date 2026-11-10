ALTER TABLE payment_attempt
    ADD COLUMN total_won BIGINT,
    ADD COLUMN provider_order_id VARCHAR(64),
    ADD COLUMN provider_authorization_id VARCHAR(64),
    ADD COLUMN provider_capture_id VARCHAR(64),
    ADD COLUMN approval_url TEXT,
    ADD COLUMN create_order_request_id VARCHAR(38),
    ADD COLUMN authorization_request_id VARCHAR(38),
    ADD COLUMN capture_request_id VARCHAR(38),
    ADD COLUMN void_request_id VARCHAR(38);

UPDATE payment_attempt AS attempt
SET total_won = checkout.total_won
FROM buyer_checkout AS checkout
WHERE attempt.checkout_id = checkout.checkout_id;

ALTER TABLE payment_attempt
    ADD CONSTRAINT payment_attempt_total_won_nonnegative
    CHECK (total_won IS NULL OR total_won >= 0);

CREATE UNIQUE INDEX ux_payment_attempt_provider_order_id
    ON payment_attempt (provider_order_id)
    WHERE provider_order_id IS NOT NULL;

CREATE UNIQUE INDEX ux_payment_attempt_provider_authorization_id
    ON payment_attempt (provider_authorization_id)
    WHERE provider_authorization_id IS NOT NULL;

CREATE UNIQUE INDEX ux_payment_attempt_provider_capture_id
    ON payment_attempt (provider_capture_id)
    WHERE provider_capture_id IS NOT NULL;

CREATE TABLE payment_refund_operation (
    refund_operation_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES payment_attempt (attempt_id),
    capture_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    amount_won BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    provider_request_id UUID NOT NULL UNIQUE,
    provider_refund_id VARCHAR(64) UNIQUE,
    provider_status VARCHAR(32),
    refund_state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_refund_operation_amount_positive CHECK (amount_won > 0),
    CONSTRAINT payment_refund_operation_currency_krw CHECK (currency = 'KRW'),
    CONSTRAINT payment_refund_operation_state_check
        CHECK (refund_state IN ('PENDING', 'UNKNOWN', 'COMPLETED', 'FAILED')),
    CONSTRAINT payment_refund_operation_attempt_key_unique UNIQUE (attempt_id, idempotency_key)
);

CREATE INDEX ix_payment_refund_operation_capture
    ON payment_refund_operation (capture_id, created_at);

ALTER TABLE order_refund_request
    ADD COLUMN payment_refund_operation_id UUID UNIQUE
        REFERENCES payment_refund_operation (refund_operation_id),
    ADD COLUMN payment_refund_status VARCHAR(16);

UPDATE order_refund_request
SET payment_refund_status = 'NOT_APPLICABLE';

ALTER TABLE order_refund_request
    ALTER COLUMN payment_refund_status SET DEFAULT 'PENDING',
    ALTER COLUMN payment_refund_status SET NOT NULL,
    ADD CONSTRAINT order_refund_request_payment_status_check
        CHECK (payment_refund_status IN ('NOT_APPLICABLE', 'PENDING', 'UNKNOWN', 'COMPLETED', 'FAILED'));

CREATE INDEX ix_order_refund_request_payment_status
    ON order_refund_request (payment_refund_status, order_id);

CREATE TABLE payment_provider_webhook_event (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    processing_status VARCHAR(16) NOT NULL,
    CONSTRAINT payment_provider_webhook_event_status_check
        CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

CREATE INDEX ix_payment_provider_webhook_event_resource
    ON payment_provider_webhook_event (resource_id, received_at);

DROP TABLE payment_fake_psp_operation;
DROP TABLE payment_fake_psp_script;
DROP TABLE payment_fake_psp_state;
