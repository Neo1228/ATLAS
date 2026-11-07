CREATE TABLE inventory_location (
    location_id UUID PRIMARY KEY DEFAULT uuidv7(),
    seller_id UUID NOT NULL REFERENCES seller (seller_id),
    location_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT inventory_location_seller_location_unique UNIQUE (seller_id, location_id),
    CONSTRAINT inventory_location_seller_code_unique UNIQUE (seller_id, location_code),
    CONSTRAINT inventory_location_code_nonblank CHECK (length(trim(location_code)) > 0),
    CONSTRAINT inventory_location_name_nonblank CHECK (length(trim(name)) > 0),
    CONSTRAINT inventory_location_priority_nonnegative CHECK (priority >= 0)
);
INSERT INTO inventory_location (seller_id, location_code, name, priority, active)
SELECT seller_id, 'DEFAULT', 'Default', 0, TRUE FROM seller;

-- Existing references must be removed before the pool key is redefined.
ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_pool_fk;
ALTER TABLE inventory_capacity_adjustment DROP CONSTRAINT inventory_capacity_adjustment_pool_fk;
ALTER TABLE inventory_pool ADD COLUMN location_id UUID;
UPDATE inventory_pool p SET location_id=l.location_id FROM inventory_location l
WHERE l.seller_id=p.seller_id AND l.location_code='DEFAULT';
ALTER TABLE inventory_pool ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE inventory_pool DROP CONSTRAINT inventory_pool_pk;
ALTER TABLE inventory_pool ADD CONSTRAINT inventory_pool_pk PRIMARY KEY (seller_id,location_id,sku_id);
ALTER TABLE inventory_pool ADD CONSTRAINT inventory_pool_location_fk
    FOREIGN KEY (seller_id,location_id) REFERENCES inventory_location (seller_id,location_id);

ALTER TABLE inventory_reservation ADD COLUMN location_id UUID;
UPDATE inventory_reservation r SET location_id=p.location_id FROM inventory_pool p
WHERE p.seller_id=r.seller_id AND p.sku_id=r.sku_id;
ALTER TABLE inventory_reservation ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_pool_fk
    FOREIGN KEY (seller_id,location_id,sku_id) REFERENCES inventory_pool (seller_id,location_id,sku_id);
CREATE INDEX inventory_reservation_location_pool_idx ON inventory_reservation (seller_id,location_id,sku_id);

ALTER TABLE inventory_capacity_adjustment ADD COLUMN location_id UUID;
UPDATE inventory_capacity_adjustment a SET location_id=p.location_id FROM inventory_pool p
WHERE p.seller_id=a.seller_id AND p.sku_id=a.sku_id;
ALTER TABLE inventory_capacity_adjustment ALTER COLUMN location_id SET NOT NULL;
ALTER TABLE inventory_capacity_adjustment DROP CONSTRAINT inventory_capacity_adjustment_key;
ALTER TABLE inventory_capacity_adjustment ADD CONSTRAINT inventory_capacity_adjustment_key
    PRIMARY KEY (seller_id,location_id,sku_id,idempotency_key);
ALTER TABLE inventory_capacity_adjustment ADD CONSTRAINT inventory_capacity_adjustment_pool_fk
    FOREIGN KEY (seller_id,location_id,sku_id) REFERENCES inventory_pool (seller_id,location_id,sku_id);

ALTER TABLE purchase_order ADD COLUMN fulfillment_location_id UUID;
UPDATE purchase_order o SET fulfillment_location_id=r.location_id
FROM inventory_reservation r WHERE r.order_id=o.order_id;
ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_fulfillment_location_fk
    FOREIGN KEY (seller_id,fulfillment_location_id) REFERENCES inventory_location (seller_id,location_id);

CREATE TABLE inventory_transfer (
    transfer_id UUID PRIMARY KEY DEFAULT uuidv7(),
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    source_location_id UUID NOT NULL,
    destination_location_id UUID NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    quantity INTEGER NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT inventory_transfer_key UNIQUE (seller_id,sku_id,idempotency_key),
    CONSTRAINT inventory_transfer_quantity_positive CHECK (quantity > 0),
    CONSTRAINT inventory_transfer_reason_nonblank CHECK (length(trim(reason)) > 0),
    CONSTRAINT inventory_transfer_idempotency_key_nonblank CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT inventory_transfer_distinct_locations CHECK (source_location_id<>destination_location_id),
    CONSTRAINT inventory_transfer_source_fk FOREIGN KEY (seller_id,source_location_id,sku_id)
        REFERENCES inventory_pool (seller_id,location_id,sku_id),
    CONSTRAINT inventory_transfer_destination_fk FOREIGN KEY (seller_id,destination_location_id,sku_id)
        REFERENCES inventory_pool (seller_id,location_id,sku_id)
);
CREATE FUNCTION reject_inventory_transfer_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inventory transfer audit is append-only';
END;
$$;
CREATE TRIGGER inventory_transfer_append_only BEFORE UPDATE OR DELETE ON inventory_transfer
    FOR EACH ROW EXECUTE FUNCTION reject_inventory_transfer_mutation();

CREATE FUNCTION protect_default_inventory_location() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.location_code = 'DEFAULT' THEN
        RAISE EXCEPTION 'DEFAULT inventory location cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER inventory_location_default_undeletable BEFORE DELETE ON inventory_location
    FOR EACH ROW EXECUTE FUNCTION protect_default_inventory_location();
