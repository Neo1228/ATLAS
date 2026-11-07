DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_fulfillment f
        WHERE f.status IN ('SHIPPED', 'DELIVERED')
          AND NOT EXISTS (
              SELECT 1
              FROM order_item oi
              WHERE oi.order_id = f.order_id
                AND oi.quantity - COALESCE((
                    SELECT SUM(ri.quantity)
                    FROM order_refund_request_item ri
                    JOIN order_refund_request rr ON rr.refund_id = ri.refund_id
                    WHERE ri.order_item_id = oi.order_item_id
                      AND NOT EXISTS (
                          SELECT 1 FROM order_return_item ori
                          WHERE ori.refund_id = rr.refund_id
                            AND ori.order_item_id = ri.order_item_id
                      )
                ), 0) > 0
          )
    ) THEN
        RAISE EXCEPTION 'V17 cannot backfill SHIPPED/DELIVERED fulfillment with zero shippable quantity';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_item AS item
        JOIN purchase_order AS seller_order ON seller_order.order_id = item.order_id
        WHERE item.seller_id <> seller_order.seller_id
    ) THEN
        RAISE EXCEPTION 'V17 requires every OrderItem seller to match its PurchaseOrder seller';
    END IF;
END;
$$;

ALTER TABLE order_item
    ADD CONSTRAINT order_item_order_id_order_item_id_unique UNIQUE (order_id, order_item_id);
ALTER TABLE purchase_order
    ADD CONSTRAINT purchase_order_order_id_seller_id_unique UNIQUE (order_id, seller_id);
ALTER TABLE order_item
    ADD CONSTRAINT order_item_order_seller_fk
    FOREIGN KEY (order_id, seller_id) REFERENCES purchase_order (order_id, seller_id);

CREATE TABLE order_shipment (
    shipment_id UUID PRIMARY KEY DEFAULT uuidv7(),
    order_id UUID NOT NULL REFERENCES purchase_order(order_id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    carrier_code VARCHAR(64),
    tracking_number VARCHAR(128),
    shipped_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT order_shipment_order_id_idempotency_key_unique UNIQUE (order_id, idempotency_key),
    CONSTRAINT order_shipment_order_id_shipment_id_unique UNIQUE (order_id, shipment_id),
    CONSTRAINT order_shipment_idempotency_key_nonblank CHECK (idempotency_key IS NULL OR trim(idempotency_key) <> ''),
    CONSTRAINT order_shipment_carrier_tracking_pair CHECK
        ((carrier_code IS NULL AND tracking_number IS NULL) OR
         (carrier_code IS NOT NULL AND trim(carrier_code) <> '' AND tracking_number IS NOT NULL AND trim(tracking_number) <> '')),
    CONSTRAINT order_shipment_status_check CHECK (status IN ('SHIPPED', 'DELIVERED')),
    CONSTRAINT order_shipment_timestamps_check CHECK
        ((status = 'SHIPPED' AND delivered_at IS NULL) OR
         (status = 'DELIVERED' AND delivered_at IS NOT NULL AND delivered_at >= shipped_at))
);
CREATE INDEX order_shipment_order_idx ON order_shipment(order_id);

CREATE TABLE order_shipment_item (
    shipment_id UUID NOT NULL,
    order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    CONSTRAINT order_shipment_item_pk PRIMARY KEY (shipment_id, order_item_id),
    CONSTRAINT order_shipment_item_quantity_check CHECK (quantity > 0),
    CONSTRAINT order_shipment_item_shipment_fk FOREIGN KEY (order_id, shipment_id)
        REFERENCES order_shipment(order_id, shipment_id) ON DELETE CASCADE,
    CONSTRAINT order_shipment_item_order_item_fk FOREIGN KEY (order_id, order_item_id)
        REFERENCES order_item(order_id, order_item_id)
);
CREATE INDEX order_shipment_item_order_item_idx ON order_shipment_item(order_id, order_item_id);

ALTER TABLE order_fulfillment
    ALTER COLUMN status TYPE VARCHAR(24),
    DROP CONSTRAINT order_fulfillment_status,
    DROP CONSTRAINT order_fulfillment_timestamps,
    ADD CONSTRAINT order_fulfillment_status
        CHECK (status IN ('UNFULFILLED', 'IN_PROGRESS', 'PARTIALLY_SHIPPED', 'SHIPPED', 'DELIVERED')),
    ADD CONSTRAINT order_fulfillment_timestamps CHECK
        ((status = 'UNFULFILLED' AND in_progress_at IS NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
         (status = 'IN_PROGRESS' AND in_progress_at IS NOT NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
         (status = 'PARTIALLY_SHIPPED' AND in_progress_at IS NOT NULL AND shipped_at IS NULL AND delivered_at IS NULL) OR
         (status = 'SHIPPED' AND in_progress_at IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NULL) OR
         (status = 'DELIVERED' AND in_progress_at IS NOT NULL AND shipped_at IS NOT NULL AND delivered_at IS NOT NULL));

WITH historical AS (
    INSERT INTO order_shipment(order_id, idempotency_key, status, shipped_at, delivered_at)
    SELECT f.order_id, NULL,
           CASE WHEN f.status = 'DELIVERED' THEN 'DELIVERED' ELSE 'SHIPPED' END,
           f.shipped_at, f.delivered_at
    FROM order_fulfillment f
    WHERE f.status IN ('SHIPPED', 'DELIVERED')
    RETURNING shipment_id, order_id
)
INSERT INTO order_shipment_item(shipment_id, order_id, order_item_id, quantity)
SELECT h.shipment_id, h.order_id, oi.order_item_id,
       (oi.quantity - COALESCE(SUM(ri.quantity) FILTER (WHERE ori.refund_id IS NULL), 0))::integer
FROM historical h
JOIN order_item oi ON oi.order_id = h.order_id
LEFT JOIN order_refund_request_item ri ON ri.order_item_id = oi.order_item_id
LEFT JOIN order_return_item ori ON ori.refund_id = ri.refund_id AND ori.order_item_id = ri.order_item_id
GROUP BY h.shipment_id, h.order_id, oi.order_item_id, oi.quantity
HAVING oi.quantity - COALESCE(SUM(ri.quantity) FILTER (WHERE ori.refund_id IS NULL), 0) > 0;
