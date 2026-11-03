CREATE TABLE inventory_pool (
    campaign_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    initial_capacity INTEGER NOT NULL,
    available INTEGER NOT NULL,
    reserved INTEGER NOT NULL,
    claimed INTEGER NOT NULL,
    CONSTRAINT inventory_pool_pk PRIMARY KEY (campaign_id, sku_id),
    CONSTRAINT inventory_pool_initial_capacity_nonnegative CHECK (initial_capacity >= 0),
    CONSTRAINT inventory_pool_available_nonnegative CHECK (available >= 0),
    CONSTRAINT inventory_pool_reserved_nonnegative CHECK (reserved >= 0),
    CONSTRAINT inventory_pool_claimed_nonnegative CHECK (claimed >= 0),
    CONSTRAINT inventory_pool_conservation CHECK (available + reserved + claimed = initial_capacity)
);

CREATE TABLE inventory_reservation (
    order_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT inventory_reservation_pk PRIMARY KEY (order_id),
    CONSTRAINT inventory_reservation_pool_fk FOREIGN KEY (campaign_id, sku_id)
        REFERENCES inventory_pool (campaign_id, sku_id),
    CONSTRAINT inventory_reservation_quantity_one CHECK (quantity = 1),
    CONSTRAINT inventory_reservation_status CHECK (status IN ('RESERVED', 'CLAIMED', 'RELEASED'))
);

CREATE INDEX inventory_reservation_pool_idx ON inventory_reservation (campaign_id, sku_id);
