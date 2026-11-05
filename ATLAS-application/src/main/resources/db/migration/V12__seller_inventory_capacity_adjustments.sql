CREATE TABLE inventory_capacity_adjustment (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    idempotency_key TEXT NOT NULL,
    delta INTEGER NOT NULL CHECK (delta <> 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    initial_capacity INTEGER NOT NULL,
    available INTEGER NOT NULL,
    reserved INTEGER NOT NULL,
    claimed INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_capacity_adjustment_key PRIMARY KEY (seller_id, sku_id, idempotency_key),
    CONSTRAINT inventory_capacity_adjustment_pool_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES inventory_pool (seller_id, sku_id),
    CONSTRAINT inventory_capacity_adjustment_conservation
        CHECK (available + reserved + claimed = initial_capacity)
);
