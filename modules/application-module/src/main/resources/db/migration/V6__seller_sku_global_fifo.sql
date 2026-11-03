-- Legacy ownership mapping is optional only when the current database is empty.
-- For a populated database, pre-create and populate campaign_seller_backfill before
-- starting the application; V6 rejects any missing, duplicate or orphan mapping.
CREATE TABLE IF NOT EXISTS campaign_seller_backfill (
    campaign_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM campaign c
        LEFT JOIN campaign_seller_backfill m ON m.campaign_id = c.campaign_id
        WHERE m.campaign_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Campaign-to-Seller mapping is incomplete; populate campaign_seller_backfill before V6';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM campaign_seller_backfill m
        LEFT JOIN campaign c ON c.campaign_id = m.campaign_id
        WHERE c.campaign_id IS NULL
    ) THEN
        RAISE EXCEPTION 'campaign_seller_backfill contains an unknown campaign_id';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM inventory_pool p
        LEFT JOIN campaign_seller_backfill m ON m.campaign_id = p.campaign_id
        WHERE m.campaign_id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM inventory_reservation r
        LEFT JOIN campaign_seller_backfill m ON m.campaign_id = r.campaign_id
        WHERE m.campaign_id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM admission_ticket t
        LEFT JOIN campaign_seller_backfill m ON m.campaign_id = t.campaign_id
        WHERE m.campaign_id IS NULL
    ) OR EXISTS (
        SELECT 1
        FROM purchase_order o
        LEFT JOIN campaign_seller_backfill m ON m.campaign_id = o.campaign_id
        WHERE m.campaign_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Legacy inventory, admission or order data has no Campaign-to-Seller mapping';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM campaign c
        LEFT JOIN inventory_pool p
            ON p.campaign_id = c.campaign_id AND p.sku_id = c.sku_id
        WHERE p.campaign_id IS NULL
    ) THEN
        RAISE EXCEPTION 'A legacy campaign has no matching inventory pool; refusing stock migration';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM campaign c
        JOIN campaign_seller_backfill m ON m.campaign_id = c.campaign_id
        GROUP BY m.seller_id, c.sku_id
        HAVING SUM(c.max_active_checkouts) > 2147483647
    ) THEN
        RAISE EXCEPTION 'Global Seller-SKU checkout limit exceeds INTEGER; reconcile Campaign limits before V6';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM inventory_pool p
        JOIN campaign_seller_backfill m ON m.campaign_id = p.campaign_id
        GROUP BY m.seller_id, p.sku_id
        HAVING SUM(p.initial_capacity) > 2147483647
    ) THEN
        RAISE EXCEPTION 'Global Seller-SKU stock exceeds INTEGER; reconcile Campaign pool quantities before V6';
    END IF;
END
$$;

CREATE TABLE seller (
    seller_id UUID NOT NULL,
    CONSTRAINT seller_pk PRIMARY KEY (seller_id)
);

INSERT INTO seller (seller_id)
SELECT DISTINCT seller_id
FROM campaign_seller_backfill
ON CONFLICT (seller_id) DO NOTHING;

CREATE TABLE seller_sku (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    max_active_checkouts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT seller_sku_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT seller_sku_seller_fk FOREIGN KEY (seller_id) REFERENCES seller (seller_id),
    CONSTRAINT seller_sku_max_active_positive CHECK (max_active_checkouts > 0),
    CONSTRAINT seller_sku_code_nonblank CHECK (length(trim(sku_id)) > 0)
);

INSERT INTO seller_sku (seller_id, sku_id, max_active_checkouts, created_at)
SELECT m.seller_id, c.sku_id, SUM(c.max_active_checkouts)::INTEGER, MIN(c.created_at)
FROM campaign c
JOIN campaign_seller_backfill m ON m.campaign_id = c.campaign_id
GROUP BY m.seller_id, c.sku_id;

ALTER TABLE campaign ADD COLUMN seller_id UUID;
UPDATE campaign c
SET seller_id = m.seller_id
FROM campaign_seller_backfill m
WHERE m.campaign_id = c.campaign_id;
ALTER TABLE campaign ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE campaign DROP CONSTRAINT campaign_checkout_limit_positive;
ALTER TABLE campaign DROP COLUMN max_active_checkouts;
ALTER TABLE campaign ADD CONSTRAINT campaign_seller_sku_fk
    FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku (seller_id, sku_id);

CREATE TEMPORARY TABLE inventory_pool_seller_sku ON COMMIT DROP AS
SELECT m.seller_id,
       p.sku_id,
       SUM(p.initial_capacity)::INTEGER AS initial_capacity,
       SUM(p.available)::INTEGER AS available,
       SUM(p.reserved)::INTEGER AS reserved,
       SUM(p.claimed)::INTEGER AS claimed
FROM inventory_pool p
JOIN campaign_seller_backfill m ON m.campaign_id = p.campaign_id
GROUP BY m.seller_id, p.sku_id;

ALTER TABLE inventory_reservation DROP CONSTRAINT inventory_reservation_pool_fk;
DROP INDEX inventory_reservation_pool_idx;
DROP TABLE inventory_pool;

CREATE TABLE inventory_pool (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    initial_capacity INTEGER NOT NULL,
    available INTEGER NOT NULL,
    reserved INTEGER NOT NULL,
    claimed INTEGER NOT NULL,
    CONSTRAINT inventory_pool_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT inventory_pool_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT inventory_pool_initial_capacity_nonnegative CHECK (initial_capacity >= 0),
    CONSTRAINT inventory_pool_available_nonnegative CHECK (available >= 0),
    CONSTRAINT inventory_pool_reserved_nonnegative CHECK (reserved >= 0),
    CONSTRAINT inventory_pool_claimed_nonnegative CHECK (claimed >= 0),
    CONSTRAINT inventory_pool_conservation CHECK (available + reserved + claimed = initial_capacity)
);

INSERT INTO inventory_pool (seller_id, sku_id, initial_capacity, available, reserved, claimed)
SELECT seller_id, sku_id, initial_capacity, available, reserved, claimed
FROM inventory_pool_seller_sku;

ALTER TABLE inventory_reservation ADD COLUMN seller_id UUID;
UPDATE inventory_reservation r
SET seller_id = m.seller_id
FROM campaign_seller_backfill m
WHERE m.campaign_id = r.campaign_id;
ALTER TABLE inventory_reservation ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_pool_fk
    FOREIGN KEY (seller_id, sku_id) REFERENCES inventory_pool (seller_id, sku_id);
CREATE INDEX inventory_reservation_pool_idx ON inventory_reservation (seller_id, sku_id);

ALTER TABLE purchase_order ADD COLUMN seller_id UUID;
UPDATE purchase_order o
SET seller_id = m.seller_id
FROM campaign_seller_backfill m
WHERE m.campaign_id = o.campaign_id;
ALTER TABLE purchase_order ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_seller_sku_fk
    FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku (seller_id, sku_id);

ALTER TABLE admission_ticket ADD COLUMN seller_id UUID;
UPDATE admission_ticket t
SET seller_id = m.seller_id
FROM campaign_seller_backfill m
WHERE m.campaign_id = t.campaign_id;
ALTER TABLE admission_ticket ALTER COLUMN seller_id SET NOT NULL;
ALTER TABLE admission_ticket DROP CONSTRAINT admission_ticket_campaign_sequence_unique;
ALTER TABLE admission_ticket ADD CONSTRAINT admission_ticket_seller_sku_fk
    FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku (seller_id, sku_id);

CREATE TEMPORARY TABLE admission_ticket_global_sequence ON COMMIT DROP AS
SELECT ticket_id,
       ROW_NUMBER() OVER (
           PARTITION BY seller_id, sku_id
           ORDER BY created_at, campaign_id, sequence, ticket_id
       ) AS global_sequence
FROM admission_ticket;

UPDATE admission_ticket t
SET sequence = s.global_sequence
FROM admission_ticket_global_sequence s
WHERE s.ticket_id = t.ticket_id;

ALTER TABLE admission_ticket ADD CONSTRAINT admission_ticket_seller_sku_sequence_unique
    UNIQUE (seller_id, sku_id, sequence);
DROP INDEX admission_ticket_fifo_idx;
CREATE INDEX admission_ticket_fifo_idx ON admission_ticket (seller_id, sku_id, sequence)
    WHERE status = 'WAITING';

DROP TABLE admission_counter;
CREATE TABLE admission_counter (
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_counter_pk PRIMARY KEY (seller_id, sku_id),
    CONSTRAINT admission_counter_seller_sku_fk FOREIGN KEY (seller_id, sku_id)
        REFERENCES seller_sku (seller_id, sku_id),
    CONSTRAINT admission_counter_sequence_nonnegative CHECK (last_sequence >= 0)
);

INSERT INTO admission_counter (seller_id, sku_id, last_sequence)
SELECT s.seller_id, s.sku_id, COALESCE(MAX(t.sequence), 0)
FROM seller_sku s
LEFT JOIN admission_ticket t ON t.seller_id = s.seller_id AND t.sku_id = s.sku_id
GROUP BY s.seller_id, s.sku_id;

DROP TABLE campaign_seller_backfill;
