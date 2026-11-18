-- Synthetic catalog seed for multi-million-row read-path runs.
--
-- Derived from load-tests/seed-catalog-read.sql. The deterministic key scheme, row
-- shapes, price formula and the rare `needle` keyword are unchanged, so a run at any
-- size produces the same keys for the same (seller, product, variant) coordinates and
-- reruns are idempotent. Only the loading strategy changed:
--
--   * every phase is split into `batch_rows` chunks, and each chunk is a separate
--     transaction (via psql \gexec), so no single transaction has to hold millions of
--     index entries in memory, and a failed run leaves the already-committed chunks
--     behind for an idempotent retry;
--   * seller / category UUIDs are hashed once into a temp map instead of once per
--     product row;
--   * the seed validates its parameters and prints per-phase timing plus a final
--     summary so a sized run is self-documenting in the results directory.
--
-- Usage (local Compose only):
--   docker compose exec -T postgres psql -U atlas -d atlas \
--     -v sellers=1000 -v products_per_seller=2000 < load-tests/seed-catalog-scale.sql
--
-- Parameters (all optional; defaults reproduce the 2M product / 4M SKU target):
--   sellers             number of sellers (default 1000)
--   products_per_seller products per seller (default 2000)     -> 2,000,000 products
--   skus_per_product    variants per product (default 2)       -> 4,000,000 SKUs
--   needle_every        one product out of N is named
--                       'atlas needle <seller>-<product>' (default 100000)
--   batch_rows          rows per INSERT chunk for product-like
--                       tables (default 50000); SKU chunks are
--                       batch_rows * skus_per_product rows
--
-- Scaling examples:
--   -v sellers=250  -v products_per_seller=4000      ->   1,000,000 products
--   -v sellers=1000 -v products_per_seller=2000      ->   2,000,000 products (default)
--   -v sellers=2000 -v products_per_seller=2000      ->   4,000,000 products
--
-- Disk and runtime on the 8 CPU / 13.5 GiB Docker VM, measured for the default size
-- (1000 x 2000, 7 indexes on product, 3 on seller_sku, 1-3 on the other tables):
--
--   product                          1320 MB  (346 MB heap, 974 MB 7 indexes)
--   seller_sku                       1102 MB  (386 MB heap, 716 MB 3 indexes)
--   seller_sku_admission_policy       472 MB
--   product_channel_availability      238 MB
--   seller + product_category           0.5 MB
--   ------------------------------------------------
--   all six tables, heap + indexes   3133 MB  ~3.1 GiB
--   WAL retained by the bulk load    ~1.1 GiB  (bounded by max_wal_size, recycled)
--   peak during the run              ~4.2 GiB
--   wall clock                       351 s
--
-- Budget ~5 GiB of free space for the default size. Measured scaling (same VM):
--
--   sellers x products   products    SKUs   tables+indexes   wall clock
--   1000 x 2000 (default)  2,000,000  4,000,000   3133 MB   351 s
--   2000 x 2000            4,000,000  8,000,000   6247 MB   971 s
--
-- Both are roughly linear in rows, so budget ~1.6 KiB per product (heap + indexes,
-- including its SKU, admission-policy and availability rows) when sizing a run.
-- The load is insert-only and reruns use `ON CONFLICT DO NOTHING`, so peak stays
-- within ~1 GiB of the final size. The tables are permanent: dropping and recreating
-- the Compose volume is the only way to reclaim the space. Chunking also bounds
-- memory: every chunk is its own transaction, so index tuples are flushed per chunk
-- instead of accumulating. Both runs above completed on stock container defaults
-- (128 MB shared_buffers, 1 GB max_wal_size) with no tuning.
--
-- This must never run against a shared or production database.

\set ON_ERROR_STOP on
\timing on

\if :{?sellers}
\else
    \set sellers 1000
\endif

\if :{?products_per_seller}
\else
    \set products_per_seller 2000
\endif

\if :{?skus_per_product}
\else
    \set skus_per_product 2
\endif

\if :{?needle_every}
\else
    \set needle_every 100000
\endif

\if :{?batch_rows}
\else
    \set batch_rows 50000
\endif

-- Deterministic UUID from a label. Same function as seed-catalog-read.sql, so keys
-- generated at 100k rows and at 2M rows are identical for the same label.
CREATE OR REPLACE FUNCTION seed_uuid(label text) RETURNS uuid
LANGUAGE sql IMMUTABLE AS $$
    SELECT (substr(h, 1, 8) || '-' || substr(h, 9, 4) || '-' || substr(h, 13, 4)
        || '-' || substr(h, 17, 4) || '-' || substr(h, 21, 12))::uuid
    FROM (SELECT md5(label) AS h) AS digest
$$;

-- Raises with a message the operator can act on. A guard function is used instead of
-- a DO block because psql does not substitute :variables inside dollar-quoted bodies.
CREATE OR REPLACE FUNCTION seed_require(p_ok boolean, p_message text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_ok IS NOT TRUE THEN
        RAISE EXCEPTION 'seed-catalog-scale: %', p_message;
    END IF;
END
$$;

SELECT seed_require(:sellers >= 1, 'sellers must be >= 1');
SELECT seed_require(:products_per_seller >= 1, 'products_per_seller must be >= 1');
SELECT seed_require(:skus_per_product >= 1, 'skus_per_product must be >= 1');
SELECT seed_require(:needle_every >= 1, 'needle_every must be >= 1');
SELECT seed_require(:batch_rows >= 1, 'batch_rows must be >= 1');
-- A needle rate coarser than the dataset would leave the search route with zero rows,
-- which silently turns the most selective route into an unmeasurable one.
SELECT seed_require(
    :sellers::bigint * :products_per_seller::bigint >= :needle_every::bigint,
    format('needle_every=%s needs at least %s products but this run seeds %s; '
        'lower needle_every for this size',
        :needle_every,
        :needle_every,
        :sellers::bigint * :products_per_seller::bigint));

CREATE TEMP TABLE seed_phase (
    id serial PRIMARY KEY,
    phase text NOT NULL,
    at timestamptz NOT NULL
);

INSERT INTO seed_phase (phase, at) VALUES ('start', clock_timestamp());

-- One hash pair per seller, reused by every product/SKU chunk. This keeps the key
-- scheme identical to seed-catalog-read.sql ('seller-<s>', 'category-<s>') while
-- avoiding millions of redundant md5 calls.
CREATE TEMP TABLE seed_seller AS
SELECT
    s::bigint AS s,
    seed_uuid('seller-' || s) AS seller_id,
    seed_uuid('category-' || s) AS category_id
FROM generate_series(1, :sellers::bigint) AS s;

CREATE UNIQUE INDEX seed_seller_s_idx ON seed_seller (s);
ANALYZE seed_seller;

SELECT format(
    'seed-catalog-scale: sellers=%s products_per_seller=%s skus_per_product=%s '
    'needle_every=%s batch_rows=%s -> %s products, %s SKUs, ~%s needle rows',
    :sellers, :products_per_seller, :skus_per_product, :needle_every, :batch_rows,
    :sellers::bigint * :products_per_seller::bigint,
    :sellers::bigint * :products_per_seller::bigint * :skus_per_product::bigint,
    (:sellers::bigint * :products_per_seller::bigint) / :needle_every::bigint)
    AS run_parameters;

-- Sellers. ON CONFLICT keeps reruns idempotent; chunking keeps a large seller count
-- out of one transaction as well.
SELECT format(
    $sql$INSERT INTO seller (seller_id)
SELECT seller_id FROM pg_temp.seed_seller WHERE s BETWEEN %s AND %s
ON CONFLICT (seller_id) DO NOTHING;$sql$,
    lo, LEAST(lo + :batch_rows::bigint - 1, :sellers::bigint))
FROM generate_series(1, :sellers::bigint, :batch_rows::bigint) AS lo
\gexec

-- One category per seller, same 'SEED' code as the read seed.
SELECT format(
    $sql$INSERT INTO product_category (category_id, seller_id, code, name)
SELECT category_id, seller_id, 'SEED', 'Synthetic seed category'
FROM pg_temp.seed_seller WHERE s BETWEEN %s AND %s
ON CONFLICT (seller_id, code) DO NOTHING;$sql$,
    lo, LEAST(lo + :batch_rows::bigint - 1, :sellers::bigint))
FROM generate_series(1, :sellers::bigint, :batch_rows::bigint) AS lo
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('sellers+categories', clock_timestamp());

-- Products, then the SKUs/SKU-scoped rows that reference them, chunk by chunk.
-- Each chunk covers a contiguous range of global product ordinals, where
--   ordinal = (s - 1) * products_per_seller + p
-- so a chunk may span sellers but never needs more than one transaction, and the
-- SKU insert for a chunk only references products committed by that same chunk.
-- The needle rule is evaluated on the same global ordinal, which keeps the rate at
-- exactly 1 in needle_every regardless of seller count.
SELECT format(
    $sql$INSERT INTO product (product_id, seller_id, name, description, status, primary_category_id)
SELECT
    seed_uuid('product-' || g.s || '-' || g.p),
    m.seller_id,
    CASE WHEN g.ordinal %% %s = 0
         THEN 'atlas needle ' || g.s || '-' || g.p
         ELSE 'atlas broad catalog item ' || g.s || '-' || g.p END,
    'Synthetic catalog row for read-path capacity measurement',
    'ACTIVE',
    m.category_id
FROM (
    SELECT ordinal,
           (ordinal - 1) / %s + 1 AS s,
           (ordinal - 1) %% %s + 1 AS p
    FROM generate_series(%s::bigint, %s::bigint) AS ordinal
) AS g
JOIN pg_temp.seed_seller AS m ON m.s = g.s
ON CONFLICT (product_id) DO NOTHING;$sql$,
    :needle_every::bigint,
    :products_per_seller::bigint,
    :products_per_seller::bigint,
    lo, LEAST(lo + :batch_rows::bigint - 1,
        :sellers::bigint * :products_per_seller::bigint))
FROM generate_series(1, :sellers::bigint * :products_per_seller::bigint,
    :batch_rows::bigint) AS lo
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('products', clock_timestamp());

-- SKUs: skus_per_product variants per product, 'sku-<s>-<p>-<v>' as before.
SELECT format(
    $sql$INSERT INTO seller_sku (seller_id, sku_id, created_at, product_id, price_won)
SELECT
    r.seller_id,
    'sku-' || r.s || '-' || r.p || '-' || v.v,
    TIMESTAMPTZ '2024-01-01 00:00:00+00',
    r.product_id,
    1000 + ((r.s * 7 + r.p * 3 + v.v) %% 900) * 100
FROM (
    SELECT g.s, g.p, m.seller_id, seed_uuid('product-' || g.s || '-' || g.p) AS product_id
    FROM (
        SELECT ordinal,
               (ordinal - 1) / %s + 1 AS s,
               (ordinal - 1) %% %s + 1 AS p
        FROM generate_series(%s::bigint, %s::bigint) AS ordinal
    ) AS g
    JOIN pg_temp.seed_seller AS m ON m.s = g.s
) AS r
CROSS JOIN generate_series(1, %s) AS v(v)
ON CONFLICT (seller_id, sku_id) DO NOTHING;$sql$,
    :products_per_seller::bigint,
    :products_per_seller::bigint,
    lo, LEAST(lo + :batch_rows::bigint - 1,
        :sellers::bigint * :products_per_seller::bigint),
    :skus_per_product::bigint)
FROM generate_series(1, :sellers::bigint * :products_per_seller::bigint,
    :batch_rows::bigint) AS lo
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('skus', clock_timestamp());

-- Admission policy for every SKU (listing reads do not use it, checkout does).
SELECT format(
    $sql$INSERT INTO seller_sku_admission_policy (seller_id, sku_id, max_active_checkouts)
SELECT
    r.seller_id,
    'sku-' || r.s || '-' || r.p || '-' || v.v,
    100
FROM (
    SELECT g.s, g.p, m.seller_id
    FROM (
        SELECT ordinal,
               (ordinal - 1) / %s + 1 AS s,
               (ordinal - 1) %% %s + 1 AS p
        FROM generate_series(%s::bigint, %s::bigint) AS ordinal
    ) AS g
    JOIN pg_temp.seed_seller AS m ON m.s = g.s
) AS r
CROSS JOIN generate_series(1, %s) AS v(v)
ON CONFLICT (seller_id, sku_id) DO NOTHING;$sql$,
    :products_per_seller::bigint,
    :products_per_seller::bigint,
    lo, LEAST(lo + :batch_rows::bigint - 1,
        :sellers::bigint * :products_per_seller::bigint),
    :skus_per_product::bigint)
FROM generate_series(1, :sellers::bigint * :products_per_seller::bigint,
    :batch_rows::bigint) AS lo
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('admission_policies', clock_timestamp());

-- ONLINE_STORE availability for every product, so the public listing has real rows.
SELECT format(
    $sql$INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT seed_uuid('product-' || g.s || '-' || g.p), 'ONLINE_STORE', TRUE
FROM (
    SELECT ordinal,
           (ordinal - 1) / %s + 1 AS s,
           (ordinal - 1) %% %s + 1 AS p
    FROM generate_series(%s::bigint, %s::bigint) AS ordinal
) AS g
ON CONFLICT (product_id, sales_channel) DO NOTHING;$sql$,
    :products_per_seller::bigint,
    :products_per_seller::bigint,
    lo, LEAST(lo + :batch_rows::bigint - 1,
        :sellers::bigint * :products_per_seller::bigint))
FROM generate_series(1, :sellers::bigint * :products_per_seller::bigint,
    :batch_rows::bigint) AS lo
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('availability', clock_timestamp());

-- Inventory: without stock every checkout is rejected with INSUFFICIENT_STOCK, so the
-- payment, fulfilment and settlement paths can never be measured. Each SKU gets a
-- pool at the seller's DEFAULT location (created by V16) with enough units that the
-- load scenario does not exhaust it.
INSERT INTO seed_phase (phase, at) VALUES ('inventory', clock_timestamp());

-- V16 seeds a DEFAULT location only for sellers that existed at migration time.
-- Sellers created by this script afterwards have none, so inventory_pool cannot be
-- keyed until they do. Creating them here keeps the seed self-contained.
INSERT INTO inventory_location (seller_id, location_code, name, priority, active)
SELECT seller_id, 'DEFAULT', 'Default', 0, TRUE FROM seller
ON CONFLICT (seller_id, location_code) DO NOTHING;

SELECT format(
    $sql$INSERT INTO inventory_pool
        (seller_id, location_id, sku_id, initial_capacity, available, reserved, claimed)
SELECT s.seller_id, l.location_id, sku.sku_id, 100000, 100000, 0, 0
FROM seller_sku sku
JOIN inventory_location l
  ON l.seller_id = sku.seller_id AND l.location_code = 'DEFAULT'
JOIN seller s ON s.seller_id = sku.seller_id
WHERE sku.seller_id IN (
    SELECT seed_uuid('seller-' || g) FROM generate_series(%s::bigint, %s::bigint) AS g
)
ON CONFLICT (seller_id, location_id, sku_id) DO NOTHING;$sql$,
    chunks.lo, chunks.hi)
FROM (
    SELECT format('%s', n) AS lo, format('%s', LEAST(n + :batch_rows::bigint - 1, :sellers::bigint)) AS hi
    FROM generate_series(1, :sellers::bigint, :batch_rows::bigint) AS n
) AS chunks
\gexec

INSERT INTO seed_phase (phase, at) VALUES ('inventory_done', clock_timestamp());

-- VACUUM (not just ANALYZE) flushes the trigram index pending list and removes any
-- dead tuples from reruns, so the sizes reported below are the sizes queries see.
VACUUM (ANALYZE) seller;
VACUUM (ANALYZE) product_category;
VACUUM (ANALYZE) product;
VACUUM (ANALYZE) seller_sku;
VACUUM (ANALYZE) seller_sku_admission_policy;
VACUUM (ANALYZE) product_channel_availability;

INSERT INTO seed_phase (phase, at) VALUES ('vacuum_analyze', clock_timestamp());

SELECT
    round(extract(epoch FROM elapsed)::numeric, 1) AS seconds,
    phase
FROM (
    SELECT id, phase, at - lag(at) OVER (ORDER BY id) AS elapsed
    FROM seed_phase
) AS phases
WHERE id > 1
ORDER BY id;

SELECT
    (SELECT count(*) FROM seller) AS sellers,
    (SELECT count(*) FROM product_category) AS categories,
    (SELECT count(*) FROM product) AS products,
    (SELECT count(*) FROM seller_sku) AS skus,
    (SELECT count(*) FROM seller_sku_admission_policy) AS admission_policies,
    (SELECT count(*) FROM product_channel_availability) AS availability_rows,
    (SELECT count(*) FROM product WHERE lower(name) LIKE '%needle%') AS needle_products,
    (SELECT count(*) FROM product WHERE lower(name) LIKE '%broad%') AS broad_products,
    round(extract(epoch FROM clock_timestamp()
        - (SELECT at FROM seed_phase WHERE phase = 'start'))::numeric, 1) AS elapsed_seconds,
    pg_size_pretty(
        pg_total_relation_size('seller')
        + pg_total_relation_size('product_category')
        + pg_total_relation_size('product')
        + pg_total_relation_size('seller_sku')
        + pg_total_relation_size('seller_sku_admission_policy')
        + pg_total_relation_size('product_channel_availability')) AS seeded_total_size,
    pg_size_pretty(pg_total_relation_size('product')) AS product_size,
    pg_size_pretty(pg_total_relation_size('seller_sku')) AS seller_sku_size;
