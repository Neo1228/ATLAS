CREATE TABLE inventory_location_stock (
    fulfillment_location_id UUID NOT NULL,
    sku_id VARCHAR(96) NOT NULL,
    initial_capacity BIGINT NOT NULL,
    available_quantity BIGINT NOT NULL,
    reserved_quantity BIGINT NOT NULL DEFAULT 0,
    claimed_quantity BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (fulfillment_location_id, sku_id),
    CONSTRAINT inventory_location_stock_nonnegative CHECK (
        initial_capacity >= 0
        AND available_quantity >= 0
        AND reserved_quantity >= 0
        AND claimed_quantity >= 0
    ),
    CONSTRAINT inventory_location_stock_conserved CHECK (
        initial_capacity = available_quantity + reserved_quantity + claimed_quantity
    )
);

CREATE TABLE inventory_reservation (
    reservation_id UUID PRIMARY KEY,
    fulfillment_location_id UUID NOT NULL,
    order_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT inventory_reservation_status CHECK (
        status IN ('RESERVED', 'CLAIMED', 'RELEASED', 'EXPIRED')
    ),
    CONSTRAINT inventory_reservation_idempotency
        UNIQUE (fulfillment_location_id, idempotency_key)
);

CREATE INDEX inventory_reservation_due_idx
    ON inventory_reservation (fulfillment_location_id, expires_at, reservation_id)
    WHERE status = 'RESERVED';

CREATE TABLE inventory_reservation_item (
    reservation_id UUID NOT NULL REFERENCES inventory_reservation (reservation_id),
    sku_id VARCHAR(96) NOT NULL,
    quantity INTEGER NOT NULL,
    PRIMARY KEY (reservation_id, sku_id),
    CONSTRAINT inventory_reservation_item_quantity CHECK (quantity > 0)
);
