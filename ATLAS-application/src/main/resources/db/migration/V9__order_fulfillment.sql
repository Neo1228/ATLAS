CREATE TABLE order_fulfillment (
    order_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    in_progress_at TIMESTAMPTZ,
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT order_fulfillment_pk PRIMARY KEY (order_id),
    CONSTRAINT order_fulfillment_order_fk FOREIGN KEY (order_id) REFERENCES purchase_order (order_id),
    CONSTRAINT order_fulfillment_status CHECK (status IN ('UNFULFILLED', 'IN_PROGRESS', 'SHIPPED', 'DELIVERED')),
    CONSTRAINT order_fulfillment_timestamps CHECK (
        (status = 'UNFULFILLED' AND in_progress_at IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
        (status = 'IN_PROGRESS' AND in_progress_at IS NOT NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
        (status = 'SHIPPED' AND in_progress_at IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NULL) OR
        (status = 'DELIVERED' AND in_progress_at IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NOT NULL)
    )
);

INSERT INTO order_fulfillment (order_id, status, created_at, updated_at)
SELECT order_id, 'UNFULFILLED', created_at, updated_at
FROM purchase_order
WHERE state = 'PAID'
ON CONFLICT (order_id) DO NOTHING;
