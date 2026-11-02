CREATE TABLE campaign (
    campaign_id UUID PRIMARY KEY,
    sku_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    queue_ticket_ttl_seconds BIGINT NOT NULL,
    max_active_checkouts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT campaign_status_valid CHECK (status IN ('CLOSED', 'OPEN')),
    CONSTRAINT campaign_ttl_positive CHECK (queue_ticket_ttl_seconds > 0),
    CONSTRAINT campaign_checkout_limit_positive CHECK (max_active_checkouts > 0),
    CONSTRAINT campaign_sku_nonblank CHECK (length(trim(sku_id)) > 0)
);

CREATE TABLE admission_counter (
    campaign_id UUID PRIMARY KEY REFERENCES campaign(campaign_id),
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_counter_sequence_nonnegative CHECK (last_sequence >= 0)
);

CREATE TABLE admission_ticket (
    ticket_id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES campaign(campaign_id),
    sku_id VARCHAR(128) NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    sequence BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    checkout_slot_acquired_at TIMESTAMPTZ,
    checkout_slot_held BOOLEAN NOT NULL,
    CONSTRAINT admission_ticket_campaign_sequence_unique UNIQUE (campaign_id, sequence),
    CONSTRAINT admission_ticket_idempotency_unique UNIQUE (campaign_id, idempotency_key),
    CONSTRAINT admission_ticket_sequence_positive CHECK (sequence > 0),
    CONSTRAINT admission_ticket_status_valid CHECK (status IN (
        'WAITING', 'CHECKOUT_ACTIVE', 'CHECKOUT_STARTED', 'CHECKOUT_SUBMITTED', 'EXPIRED', 'CHECKOUT_FAILED'
    )),
    CONSTRAINT admission_ticket_slot_status_consistent CHECK (
        (checkout_slot_held AND status IN ('CHECKOUT_ACTIVE', 'CHECKOUT_STARTED')) OR
        (NOT checkout_slot_held AND status NOT IN ('CHECKOUT_ACTIVE', 'CHECKOUT_STARTED'))
    ),
    CONSTRAINT admission_ticket_sku_nonblank CHECK (length(trim(sku_id)) > 0),
    CONSTRAINT admission_ticket_buyer_nonblank CHECK (length(trim(buyer_ref)) > 0),
    CONSTRAINT admission_ticket_key_nonblank CHECK (length(trim(idempotency_key)) > 0)
);

CREATE INDEX admission_ticket_fifo_idx ON admission_ticket (campaign_id, sequence)
    WHERE status = 'WAITING';
CREATE INDEX admission_ticket_expiry_idx ON admission_ticket (expires_at)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');
