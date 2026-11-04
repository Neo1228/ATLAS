ALTER TABLE inventory_reservation
    ADD COLUMN restored_quantity INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT inventory_reservation_restored_quantity
        CHECK (restored_quantity >= 0 AND restored_quantity <= quantity);

ALTER TABLE settlement_journal
    ALTER COLUMN source_attempt_id DROP NOT NULL,
    ADD COLUMN source_refund_id UUID,
    ADD CONSTRAINT settlement_journal_source_refund_id_key UNIQUE (source_refund_id),
    ADD CONSTRAINT settlement_journal_exactly_one_source
        CHECK ((source_attempt_id IS NOT NULL AND source_refund_id IS NULL)
            OR (source_attempt_id IS NULL AND source_refund_id IS NOT NULL));

ALTER TABLE settlement_entry
    DROP CONSTRAINT settlement_entry_account_side,
    ADD CONSTRAINT settlement_entry_account_side CHECK (
        (account_code = 'PSP_CLEARING' AND entry_side = 'DEBIT')
        OR (account_code = 'SELLER_PAYABLE' AND entry_side = 'CREDIT')
        OR (account_code = 'PSP_CLEARING' AND entry_side = 'CREDIT')
        OR (account_code = 'SELLER_PAYABLE' AND entry_side = 'DEBIT')
    );

CREATE TABLE order_refund_summary (
    order_id UUID PRIMARY KEY REFERENCES purchase_order (order_id),
    seller_id UUID NOT NULL,
    recorded_amount_won BIGINT NOT NULL,
    refund_status VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_refund_summary_amount_positive CHECK (recorded_amount_won > 0),
    CONSTRAINT order_refund_summary_status CHECK (refund_status IN ('PARTIALLY_RECORDED', 'FULLY_RECORDED'))
);

CREATE TABLE order_refund_request (
    refund_id UUID PRIMARY KEY DEFAULT uuidv7(),
    order_id UUID NOT NULL REFERENCES purchase_order (order_id),
    seller_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    amount_won BIGINT NOT NULL,
    refund_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_refund_request_amount_positive CHECK (amount_won > 0),
    CONSTRAINT order_refund_request_status CHECK (refund_status IN ('PARTIALLY_RECORDED', 'FULLY_RECORDED'))
);

CREATE INDEX order_refund_request_order_id_idx ON order_refund_request (order_id);

CREATE TABLE order_refund_request_item (
    refund_id UUID NOT NULL REFERENCES order_refund_request (refund_id),
    order_item_id UUID NOT NULL REFERENCES order_item (order_item_id),
    quantity INTEGER NOT NULL,
    CONSTRAINT order_refund_request_item_pk PRIMARY KEY (refund_id, order_item_id),
    CONSTRAINT order_refund_request_item_quantity_positive CHECK (quantity > 0)
);
