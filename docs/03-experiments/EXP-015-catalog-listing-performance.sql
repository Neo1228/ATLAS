-- Local-only catalog listing fixture. Use only with atlas_catalog_perf.
-- Supply -v product_count=10000 (or 100000) to psql.

SELECT 1 / CASE WHEN :product_count IN (10000, 100000) THEN 1 ELSE 0 END;

BEGIN;

DO $$
BEGIN
    IF current_database() <> 'atlas_catalog_perf' THEN
        RAISE EXCEPTION 'Refusing seed: current database is %, expected atlas_catalog_perf', current_database();
    END IF;
END
$$;

INSERT INTO seller (seller_id)
SELECT CASE WHEN seller_number = 1 THEN '00000000-0000-7000-8000-000000000015'::uuid
            ELSE md5('atlas-catalog-perf-seller-' || seller_number)::uuid END
FROM generate_series(1, 100) AS sellers(seller_number)
ON CONFLICT (seller_id) DO NOTHING;

INSERT INTO product (product_id, seller_id, name, description)
SELECT md5('atlas-catalog-perf-product-' || n)::uuid,
       CASE WHEN (n - 1) % 100 + 1 = 1 THEN '00000000-0000-7000-8000-000000000015'::uuid
            ELSE md5('atlas-catalog-perf-seller-' || ((n - 1) % 100 + 1))::uuid END,
       CASE WHEN n % 100 = 0 THEN 'catalog-perf-broad-needle-'
            WHEN n % 10 = 0 THEN 'catalog-perf-broad-'
            ELSE 'catalog-perf-product-' END || lpad(n::text, 6, '0'),
       'Synthetic local catalog listing fixture; product ' || n
FROM generate_series(1, :product_count) AS seed(n)
ON CONFLICT (product_id) DO UPDATE
SET seller_id = EXCLUDED.seller_id,
    name = EXCLUDED.name,
    description = EXCLUDED.description;

INSERT INTO seller_sku (seller_id, sku_id, product_id, price_won, created_at)
SELECT CASE WHEN (seed.n - 1) % 100 + 1 = 1 THEN '00000000-0000-7000-8000-000000000015'::uuid
            ELSE md5('atlas-catalog-perf-seller-' || ((seed.n - 1) % 100 + 1))::uuid END,
       'catalog-perf-' || lpad(seed.n::text, 6, '0') || '-' || variant.suffix,
       md5('atlas-catalog-perf-product-' || seed.n)::uuid,
       CASE variant.suffix WHEN 'a' THEN 1000 ELSE 2000 END,
       TIMESTAMPTZ '2026-01-01 00:00:00+00'
FROM generate_series(1, :product_count) AS seed(n)
CROSS JOIN (VALUES ('a'), ('b')) AS variant(suffix)
ON CONFLICT (seller_id, sku_id) DO UPDATE
SET product_id = EXCLUDED.product_id,
    price_won = EXCLUDED.price_won,
    created_at = EXCLUDED.created_at;

COMMIT;

ANALYZE seller;
ANALYZE product;
ANALYZE seller_sku;
