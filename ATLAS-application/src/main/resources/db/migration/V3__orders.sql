CREATE TABLE purchase_order (
    order_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    ticket_id UUID NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    checkout_expires_at TIMESTAMPTZ NOT NULL,
    payment_attempt_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT purchase_order_pk PRIMARY KEY (order_id),
    CONSTRAINT purchase_order_ticket_unique UNIQUE (ticket_id),
    CONSTRAINT purchase_order_state CHECK (state IN ('RESERVED', 'PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CANCEL_PENDING', 'CANCELLED'))
);

CREATE INDEX purchase_order_expiry_idx ON purchase_order (checkout_expires_at)
    WHERE state = 'RESERVED';
CREATE INDEX purchase_order_buyer_unresolved_idx ON purchase_order (campaign_id, buyer_ref)
    WHERE state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'PAYMENT_VOID_PENDING', 'CANCEL_PENDING');
