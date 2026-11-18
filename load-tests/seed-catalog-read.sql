-- Synthetic read-path seed for the capacity probe (EXP-035).
-- Creates sellers, categories, products, two SKUs per product, admission policy, and
-- ONLINE_STORE availability so the public listing query has real rows to traverse.
-- Inventory pools are intentionally omitted: listing reads do not touch them, and the
-- current schema keys a pool by fulfillment location.
-- Deterministic keys make reruns idempotent and let measurements be compared.
--
-- Usage (local Compose only):
--   docker compose exec -T postgres psql -U atlas -d atlas \
--     -v sellers=500 -v products_per_seller=400 < load-tests/seed-catalog-read.sql
--
-- This must never run against a shared or production database.

\set ON_ERROR_STOP on

BEGIN;

CREATE OR REPLACE FUNCTION seed_uuid(label text) RETURNS uuid
LANGUAGE sql IMMUTABLE AS $$
    SELECT (substr(h, 1, 8) || '-' || substr(h, 9, 4) || '-' || substr(h, 13, 4)
        || '-' || substr(h, 17, 4) || '-' || substr(h, 21, 12))::uuid
    FROM (SELECT md5(label) AS h) AS digest
$$;

INSERT INTO seller (seller_id)
SELECT seed_uuid('seller-' || s)
FROM generate_series(1, :sellers) AS s
ON CONFLICT (seller_id) DO NOTHING;

INSERT INTO product_category (category_id, seller_id, code, name)
SELECT seed_uuid('category-' || s), seed_uuid('seller-' || s), 'SEED', 'Synthetic seed category'
FROM generate_series(1, :sellers) AS s
ON CONFLICT (seller_id, code) DO NOTHING;

INSERT INTO product (product_id, seller_id, name, description, status, primary_category_id)
SELECT
    seed_uuid('product-' || s || '-' || p),
    seed_uuid('seller-' || s),
    -- 1 in 10,000 products carries the rare token so the "needle" query is selective.
    CASE WHEN (s * :products_per_seller + p) % 10000 = 0
         THEN 'atlas needle ' || s || '-' || p
         ELSE 'atlas broad catalog item ' || s || '-' || p END,
    'Synthetic catalog row for read-path capacity measurement',
    'ACTIVE',
    seed_uuid('category-' || s)
FROM generate_series(1, :sellers) AS s
CROSS JOIN generate_series(1, :products_per_seller) AS p
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO seller_sku (seller_id, sku_id, created_at, product_id, price_won)
SELECT
    seed_uuid('seller-' || s),
    'sku-' || s || '-' || p || '-' || v,
    TIMESTAMPTZ '2024-01-01 00:00:00+00',
    seed_uuid('product-' || s || '-' || p),
    1000 + ((s * 7 + p * 3 + v) % 900) * 100
FROM generate_series(1, :sellers) AS s
CROSS JOIN generate_series(1, :products_per_seller) AS p
CROSS JOIN generate_series(1, 2) AS v
ON CONFLICT (seller_id, sku_id) DO NOTHING;

INSERT INTO seller_sku_admission_policy (seller_id, sku_id, max_active_checkouts)
SELECT seed_uuid('seller-' || s), 'sku-' || s || '-' || p || '-' || v, 100
FROM generate_series(1, :sellers) AS s
CROSS JOIN generate_series(1, :products_per_seller) AS p
CROSS JOIN generate_series(1, 2) AS v
ON CONFLICT (seller_id, sku_id) DO NOTHING;

INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT seed_uuid('product-' || s || '-' || p), 'ONLINE_STORE', TRUE
FROM generate_series(1, :sellers) AS s
CROSS JOIN generate_series(1, :products_per_seller) AS p
ON CONFLICT (product_id, sales_channel) DO NOTHING;

COMMIT;

ANALYZE seller;
ANALYZE product;
ANALYZE seller_sku;
ANALYZE product_channel_availability;

SELECT
    (SELECT count(*) FROM seller) AS sellers,
    (SELECT count(*) FROM product) AS products,
    (SELECT count(*) FROM seller_sku) AS skus,
    (SELECT count(*) FROM product_channel_availability) AS availability_rows;
