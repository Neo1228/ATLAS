-- EXP-028: global catalog listing UUID sort-key index experiment.
-- Run only in a disposable PostgreSQL 18.6 database, never in production.
--
-- Baseline: run this script before creating product_listing_sort_idx.
-- Candidate: create the index below, ANALYZE product, then rerun the EXPLAIN query.
-- Repeat with psql variables: -v keyword='' (global), -v keyword=broad, -v keyword=needle.

CREATE TABLE product (
    product_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,
    primary_category_id UUID
);
CREATE TABLE product_channel_availability (
    product_id UUID NOT NULL,
    sales_channel TEXT NOT NULL,
    available BOOLEAN NOT NULL,
    PRIMARY KEY (product_id, sales_channel)
);
CREATE TABLE seller_sku (
    product_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    price_won BIGINT NOT NULL,
    sale_price_won BIGINT,
    sale_starts_at TIMESTAMPTZ,
    sale_ends_at TIMESTAMPTZ
);
CREATE TABLE product_category(category_id UUID, parent_category_id UUID, seller_id UUID);
CREATE TABLE product_category_assignment(seller_id UUID, product_id UUID, category_id UUID);
CREATE INDEX product_seller_idx ON product (seller_id, product_id);

INSERT INTO product (product_id, seller_id, name, description, status)
SELECT gen_random_uuid(), md5((g % 100)::text)::uuid,
       CASE WHEN g % 100 = 0 THEN 'needle item ' || g
            WHEN g % 10 = 0 THEN 'broad item ' || g
            ELSE 'ordinary item ' || g END,
       'description', 'ACTIVE'
FROM generate_series(1, 100000) AS g;
INSERT INTO product_channel_availability (product_id, sales_channel, available)
SELECT product_id, 'ONLINE_STORE', TRUE FROM product;
INSERT INTO seller_sku (product_id, seller_id, price_won)
SELECT product_id, seller_id, 1000 FROM product;
ANALYZE product;
ANALYZE product_channel_availability;
ANALYZE seller_sku;

\set keyword ''
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
WITH RECURSIVE product_listing AS (
    SELECT p.*,
        encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
            get_byte(uuid_send(p.seller_id), 0) # 128), 8,
            get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
        encode(set_byte(set_byte(uuid_send(p.product_id), 0,
            get_byte(uuid_send(p.product_id), 0) # 128), 8,
            get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
    FROM product p
), effective_sku_price AS (
    SELECT sku.product_id,
           CASE WHEN sku.sale_price_won IS NOT NULL
                     AND sku.sale_starts_at <= CURRENT_TIMESTAMP
                     AND CURRENT_TIMESTAMP < sku.sale_ends_at
                THEN sku.sale_price_won ELSE sku.price_won END AS effective_price
    FROM seller_sku sku
), category_tree(category_id) AS (
    SELECT CAST(NULL AS uuid) WHERE CAST(NULL AS uuid) IS NOT NULL
    UNION
    SELECT child.category_id FROM product_category child
    JOIN category_tree parent ON child.parent_category_id = parent.category_id
    WHERE child.seller_id = CAST(NULL AS uuid)
), cursor_key AS (
    SELECT NULL::text COLLATE "C" AS seller_sort_key,
           NULL::text COLLATE "C" AS product_sort_key
)
SELECT p.product_id, p.seller_id, p.primary_category_id, p.name, p.description, p.status
FROM product_listing p CROSS JOIN cursor_key c
WHERE (CAST(NULL AS uuid) IS NULL OR p.seller_id = CAST(NULL AS uuid))
  AND (FALSE OR (p.status = 'ACTIVE' AND EXISTS (
      SELECT 1 FROM product_channel_availability availability
      WHERE availability.product_id = p.product_id
        AND availability.sales_channel = 'ONLINE_STORE' AND availability.available = TRUE
  )))
  AND (CAST(NULL AS uuid) IS NULL OR p.primary_category_id IN (SELECT category_id FROM category_tree)
       OR EXISTS (
          SELECT 1 FROM product_category_assignment assignment
          WHERE assignment.seller_id = p.seller_id AND assignment.product_id = p.product_id
            AND assignment.category_id IN (SELECT category_id FROM category_tree)
       ))
  AND (:'keyword'::text = '' OR position(:'keyword'::text in lower(p.name)) > 0)
  AND (NULL::bigint IS NULL AND NULL::bigint IS NULL OR EXISTS (
      SELECT 1 FROM effective_sku_price price
      WHERE price.product_id = p.product_id
        AND (NULL::bigint IS NULL OR price.effective_price >= NULL::bigint)
        AND (NULL::bigint IS NULL OR price.effective_price <= NULL::bigint)
  ))
  AND (CAST(NULL AS uuid) IS NULL OR p.seller_sort_key > c.seller_sort_key
       OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
ORDER BY p.seller_sort_key, p.product_sort_key
LIMIT 101;

-- Candidate index matching the exact ORDER BY expressions in ProductRepository:
-- CREATE INDEX product_listing_sort_idx ON product (
--   (encode(set_byte(set_byte(uuid_send(seller_id),0,get_byte(uuid_send(seller_id),0)#128),8,get_byte(uuid_send(seller_id),8)#128),'hex') COLLATE "C"),
--   (encode(set_byte(set_byte(uuid_send(product_id),0,get_byte(uuid_send(product_id),0)#128),8,get_byte(uuid_send(product_id),8)#128),'hex') COLLATE "C")
-- );
-- ANALYZE product;
