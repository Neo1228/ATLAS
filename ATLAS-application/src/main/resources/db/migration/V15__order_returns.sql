CREATE TABLE order_return (
    return_id UUID PRIMARY KEY DEFAULT uuidv7(),
    order_id UUID NOT NULL REFERENCES purchase_order(order_id),
    seller_id UUID NOT NULL,
    buyer_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    return_status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ,
    inspected_at TIMESTAMPTZ,
    CONSTRAINT order_return_order_key_unique UNIQUE (order_id, idempotency_key),
    CONSTRAINT order_return_status_check CHECK (return_status IN ('REQUESTED', 'RECEIVED', 'REFUND_RECORDED', 'PARTIALLY_RECORDED', 'INSPECTION_FAILED')),
    CONSTRAINT order_return_timestamps_check CHECK (
        (return_status = 'REQUESTED' AND received_at IS NULL AND inspected_at IS NULL)
        OR (return_status = 'RECEIVED' AND received_at IS NOT NULL AND inspected_at IS NULL)
        OR (return_status IN ('REFUND_RECORDED', 'PARTIALLY_RECORDED', 'INSPECTION_FAILED')
            AND received_at IS NOT NULL AND inspected_at IS NOT NULL)
    )
);
CREATE INDEX order_return_order_idx ON order_return (order_id);

CREATE TABLE order_return_item (
    return_item_id UUID PRIMARY KEY DEFAULT uuidv7(),
    return_id UUID NOT NULL REFERENCES order_return(return_id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_item(order_item_id),
    quantity INTEGER NOT NULL,
    inspection_result VARCHAR(16),
    refund_id UUID REFERENCES order_refund_request(refund_id),
    refund_amount_won BIGINT,
    inspected_at TIMESTAMPTZ,
    CONSTRAINT order_return_item_unique UNIQUE (return_id, order_item_id),
    CONSTRAINT order_return_item_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_return_item_inspection CHECK (inspection_result IS NULL OR inspection_result IN ('PASSED', 'FAILED')),
    CONSTRAINT order_return_item_refund CHECK (((refund_id IS NULL AND refund_amount_won IS NULL) OR (refund_id IS NOT NULL AND refund_amount_won > 0)) AND (refund_id IS NULL OR inspection_result = 'PASSED'))
);
CREATE INDEX order_return_item_order_item_idx ON order_return_item (order_item_id);
