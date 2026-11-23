-- 원격 재고로 판매한 주문의 환불 재입고 의도. 환불 확정 transaction 안에서 기록하고,
-- 원격 restock 호출은 commit 뒤 transaction 밖에서 같은 restock key로 재시도한다.
CREATE TABLE order_inventory_restock (
    restock_key VARCHAR(160) NOT NULL,
    fulfillment_location_id UUID NOT NULL,
    refund_id UUID NOT NULL,
    order_id UUID NOT NULL,
    workflow_id UUID NOT NULL REFERENCES order_inventory_reservation_workflow (workflow_id),
    remote_reservation_id UUID NOT NULL,
    restock_state VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (restock_key, fulfillment_location_id),
    CONSTRAINT order_inventory_restock_state CHECK (restock_state IN ('PENDING', 'COMPLETED', 'NEEDS_REVIEW')),
    CONSTRAINT order_inventory_restock_attempt_nonnegative CHECK (attempt_count >= 0)
);

CREATE TABLE order_inventory_restock_item (
    restock_key VARCHAR(160) NOT NULL,
    fulfillment_location_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(96) NOT NULL,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (restock_key, fulfillment_location_id, seller_id, sku_id),
    CONSTRAINT order_inventory_restock_item_quantity CHECK (quantity > 0),
    CONSTRAINT order_inventory_restock_item_parent FOREIGN KEY (restock_key, fulfillment_location_id)
        REFERENCES order_inventory_restock (restock_key, fulfillment_location_id) ON DELETE CASCADE
);

CREATE INDEX order_inventory_restock_due_idx
    ON order_inventory_restock (next_attempt_at, restock_key)
    WHERE restock_state = 'PENDING';
