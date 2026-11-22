-- PayPal Payouts 상태와 내부 Seller payable을 provider 증거로 연결한다.
-- Provider 호출은 별도 application worker가 transaction 밖에서 수행하고, stable sender_batch_id로 재조정한다.

ALTER TABLE settlement_payout
    ADD COLUMN settled_amount_won BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN provider_debit_book_value_won BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN payout_fx_rounding_expense_won BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN provider_fee_won BIGINT NOT NULL DEFAULT 0;

-- 기존 수동 CONFIRMED 지급은 당시 기록 금액 전체가 확정된 것으로 보존한다.
UPDATE settlement_payout
SET settled_amount_won = amount_won
WHERE state = 'CONFIRMED';

ALTER TABLE settlement_payout
    DROP CONSTRAINT settlement_payout_state_valid,
    ADD CONSTRAINT settlement_payout_state_valid CHECK (
        state IN ('REQUESTED', 'APPROVED', 'DISPATCHING', 'SENT', 'UNKNOWN',
                  'UNCLAIMED', 'NEEDS_REVIEW', 'CANCELLED', 'CONFIRMED', 'FAILED')),
    ADD CONSTRAINT settlement_payout_settled_amount_valid CHECK (
        settled_amount_won >= 0 AND settled_amount_won <= amount_won),
    ADD CONSTRAINT settlement_payout_provider_amount_valid CHECK (
        provider_debit_book_value_won >= 0 AND payout_fx_rounding_expense_won >= 0 AND provider_fee_won >= 0
        AND ((provider_debit_book_value_won = 0 AND payout_fx_rounding_expense_won = 0 AND provider_fee_won = 0)
            OR provider_debit_book_value_won = amount_won + payout_fx_rounding_expense_won));
-- A refund may preempt a payout before dispatch; allow a new payout request for that period.
DROP INDEX settlement_payout_seller_period_active_unique;
CREATE UNIQUE INDEX settlement_payout_seller_period_active_unique
    ON settlement_payout (seller_id, period_start, period_end)
    WHERE state NOT IN ('FAILED', 'CANCELLED');

-- Payout provider fee is borne by the platform in V1, never deducted from seller payable.
ALTER TABLE settlement_entry DROP CONSTRAINT settlement_entry_account_side;
ALTER TABLE settlement_entry
    ADD CONSTRAINT settlement_entry_account_side CHECK (
        (account_code = 'PSP_CLEARING' AND entry_side IN ('DEBIT', 'CREDIT'))
        OR (account_code = 'SELLER_PAYABLE' AND entry_side IN ('DEBIT', 'CREDIT'))
        OR (account_code = 'PLATFORM_FEE' AND entry_side = 'CREDIT')
        OR (account_code = 'SELLER_RESERVE' AND entry_side IN ('DEBIT', 'CREDIT'))
        OR (account_code = 'PLATFORM_PAYOUT_FEE' AND entry_side = 'DEBIT')
        OR (account_code = 'PLATFORM_PAYOUT_FX_ROUNDING' AND entry_side = 'DEBIT')
    );

-- Seller가 연결한 verified account email을 PayPal recipient identity로 사용한다.
-- 국가는 provider 적격성 검토용이며 recipient email 자체는 여기에 저장하지 않는다.
CREATE TABLE seller_payout_profile (
    seller_id UUID PRIMARY KEY REFERENCES seller (seller_id),
    recipient_account_id UUID NOT NULL,
    country_code CHAR(2) NOT NULL,
    payout_currency CHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    profile_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    verified_by VARCHAR(128),
    CONSTRAINT seller_payout_profile_owner_fk FOREIGN KEY (recipient_account_id, seller_id)
        REFERENCES seller_account (account_id, seller_id),
    CONSTRAINT seller_payout_profile_country_check CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT seller_payout_profile_currency_check CHECK (payout_currency = 'USD'),
    CONSTRAINT seller_payout_profile_status_check CHECK (
        status IN ('PENDING_REVIEW', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT seller_payout_profile_version_check CHECK (profile_version > 0),
    CONSTRAINT seller_payout_profile_verification_check CHECK (
        (status = 'ACTIVE' AND verified_at IS NOT NULL AND verified_by IS NOT NULL)
        OR (status <> 'ACTIVE')
    )
);

CREATE TABLE seller_payout_profile_action (
    action_id UUID PRIMARY KEY DEFAULT uuidv7(),
    seller_id UUID NOT NULL REFERENCES seller_payout_profile (seller_id),
    profile_version INTEGER NOT NULL,
    action VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_facts_sha256 CHAR(64) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT seller_payout_profile_action_unique UNIQUE (seller_id, idempotency_key),
    CONSTRAINT seller_payout_profile_action_reason_check CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT seller_payout_profile_action_version_check CHECK (profile_version > 0)
);

CREATE INDEX ix_seller_payout_profile_action_created
    ON seller_payout_profile_action (seller_id, created_at DESC, action_id DESC);

-- Refund intent holds seller payable before the provider call. These are commitments, not ledger entries.
CREATE TABLE settlement_refund_reservation (
    refund_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    amount_won BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_refund_reservation_amount_check CHECK (amount_won > 0),
    CONSTRAINT settlement_refund_reservation_status_check CHECK (status IN ('RESERVED', 'POSTED', 'RELEASED'))
);

CREATE INDEX ix_settlement_refund_reservation_pending
    ON settlement_refund_reservation (seller_id, created_at, refund_id)
    WHERE status = 'RESERVED';

-- Preserve unresolved provider refunds that predate the new payout hold contract.
INSERT INTO settlement_refund_reservation (
    refund_id, order_id, seller_id, amount_won, status, created_at, updated_at)
SELECT refund_id, order_id, seller_id, amount_won, 'RESERVED', created_at, created_at
FROM order_refund_request
WHERE payment_refund_status IN ('PENDING', 'UNKNOWN')
ON CONFLICT (refund_id) DO NOTHING;

-- Recipient email은 AES-GCM ciphertext로만 저장한다. Retry payload/FX/idempotency facts는 승인 시점에 고정한다.
CREATE TABLE settlement_payout_dispatch_outbox (
    payout_id UUID PRIMARY KEY REFERENCES settlement_payout (payout_id),
    profile_version INTEGER NOT NULL,
    recipient_email_ciphertext TEXT NOT NULL,
    recipient_country_code CHAR(2) NOT NULL,
    payout_currency CHAR(3) NOT NULL DEFAULT 'USD',
    requested_amount_won BIGINT NOT NULL,
    provider_amount_minor BIGINT NOT NULL,
    fx_rate_won_per_usd NUMERIC(18, 8) NOT NULL,
    fx_source VARCHAR(32) NOT NULL,
    fx_as_of_date DATE NOT NULL,
    provider_debit_book_value_won BIGINT NOT NULL,
    payout_fx_rounding_expense_won BIGINT NOT NULL,
    provider_fee_minor BIGINT,
    provider_fee_currency CHAR(3),
    provider_fee_won BIGINT,
    sender_batch_id VARCHAR(256) NOT NULL UNIQUE,
    sender_item_id VARCHAR(256) NOT NULL UNIQUE,
    paypal_request_id VARCHAR(256) NOT NULL UNIQUE,
    request_payload_sha256 CHAR(64) NOT NULL,
    provider_batch_id VARCHAR(128),
    provider_item_id VARCHAR(128),
    provider_batch_status VARCHAR(32),
    provider_item_status VARCHAR(32),
    status VARCHAR(24) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    first_enqueued_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_payout_dispatch_profile_version_check CHECK (profile_version > 0),
    CONSTRAINT settlement_payout_dispatch_currency_check CHECK (payout_currency = 'USD'),
    CONSTRAINT settlement_payout_dispatch_country_check CHECK (recipient_country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT settlement_payout_dispatch_amount_check CHECK (
        requested_amount_won > 0 AND provider_amount_minor > 0
        AND provider_debit_book_value_won >= requested_amount_won
        AND payout_fx_rounding_expense_won = provider_debit_book_value_won - requested_amount_won),
    CONSTRAINT settlement_payout_dispatch_fx_check CHECK (fx_rate_won_per_usd > 0),
    CONSTRAINT settlement_payout_dispatch_fee_check CHECK (
        (provider_fee_minor IS NULL AND provider_fee_currency IS NULL AND provider_fee_won IS NULL)
        OR (provider_fee_minor IS NOT NULL AND provider_fee_minor >= 0
            AND provider_fee_currency = 'USD' AND provider_fee_won IS NOT NULL AND provider_fee_won >= 0)),
    CONSTRAINT settlement_payout_dispatch_status_check CHECK (
        status IN ('PENDING', 'LEASED', 'WAITING_PROVIDER', 'COMPLETED', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW')),
    CONSTRAINT settlement_payout_dispatch_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT settlement_payout_dispatch_lease_check CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT settlement_payout_dispatch_lease_status_check CHECK ((status = 'LEASED') = (lease_token IS NOT NULL)),
    CONSTRAINT settlement_payout_dispatch_waiting_provider_check CHECK (
        status <> 'WAITING_PROVIDER' OR provider_batch_id IS NOT NULL),
    CONSTRAINT settlement_payout_dispatch_provider_batch_unique UNIQUE (provider_batch_id),
    CONSTRAINT settlement_payout_dispatch_provider_item_unique UNIQUE (provider_item_id)
);

CREATE INDEX ix_settlement_payout_dispatch_due
    ON settlement_payout_dispatch_outbox (available_at, first_enqueued_at, payout_id)
    WHERE status IN ('PENDING', 'WAITING_PROVIDER');

CREATE INDEX ix_settlement_payout_dispatch_needs_review
    ON settlement_payout_dispatch_outbox (first_enqueued_at, payout_id)
    WHERE status = 'NEEDS_REVIEW';

CREATE TABLE settlement_payout_operator_action (
    action_id UUID PRIMARY KEY DEFAULT uuidv7(),
    payout_id UUID NOT NULL REFERENCES settlement_payout (payout_id),
    idempotency_key VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_payout_operator_action_unique UNIQUE (payout_id, idempotency_key),
    CONSTRAINT settlement_payout_operator_action_reason_check CHECK (length(trim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX ix_settlement_payout_operator_action_created
    ON settlement_payout_operator_action (payout_id, created_at DESC, action_id DESC);
