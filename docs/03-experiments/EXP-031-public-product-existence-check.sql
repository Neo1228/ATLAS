-- EXP-031: public product existence check vs detail assembly.
-- Run only in a disposable PostgreSQL 18.6 database.

CREATE TABLE product(product_id uuid PRIMARY KEY, seller_id uuid NOT NULL, status varchar(16) NOT NULL, name text);
CREATE TABLE product_channel_availability(product_id uuid NOT NULL, sales_channel varchar(32) NOT NULL,
    available boolean NOT NULL, PRIMARY KEY(product_id, sales_channel));
CREATE TABLE seller_sku(seller_id uuid NOT NULL, sku_id varchar(128) NOT NULL, product_id uuid NOT NULL,
    price_won bigint NOT NULL, PRIMARY KEY(seller_id, sku_id));
CREATE INDEX seller_sku_product_idx ON seller_sku(product_id, seller_id);
CREATE TABLE product_category_assignment(seller_id uuid NOT NULL, product_id uuid NOT NULL,
    category_id uuid NOT NULL, PRIMARY KEY(seller_id, product_id, category_id));

INSERT INTO product SELECT gen_random_uuid(), md5((g % 200)::text)::uuid, 'ACTIVE', 'Product ' || g
FROM generate_series(1, 100000) AS g;
INSERT INTO product_channel_availability SELECT product_id, ch, TRUE
FROM product CROSS JOIN (VALUES ('ONLINE_STORE'), ('CAMPAIGN')) AS v(ch);
INSERT INTO seller_sku SELECT p.seller_id, 'sku-' || p.product_id::text || '-' || v.n, p.product_id, 1000
FROM product p CROSS JOIN generate_series(1, 5) AS v(n);
INSERT INTO product_category_assignment SELECT seller_id, product_id, gen_random_uuid() FROM product;
VACUUM (ANALYZE) product; VACUUM (ANALYZE) product_channel_availability;
VACUUM (ANALYZE) seller_sku; VACUUM (ANALYZE) product_category_assignment;

\set product_id '(select product_id from product limit 1)'

-- A. 변경 후: 단일 EXISTS
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT EXISTS (
    SELECT 1 FROM product p
    WHERE p.product_id = (SELECT product_id FROM product LIMIT 1)
      AND p.status = 'ACTIVE'
      AND EXISTS (SELECT 1 FROM product_channel_availability c
                  WHERE c.product_id = p.product_id
                    AND c.sales_channel = 'ONLINE_STORE' AND c.available = TRUE)
);

-- B. 변경 전: 상세 조립에 해당하는 3개 조회
EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT p.*, c.sales_channel, c.available
FROM product p LEFT JOIN product_channel_availability c ON c.product_id = p.product_id
WHERE p.product_id = (SELECT product_id FROM product LIMIT 1) AND p.status = 'ACTIVE';

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT sku_id, price_won FROM seller_sku
WHERE product_id = (SELECT product_id FROM product LIMIT 1);

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT category_id FROM product_category_assignment
WHERE seller_id = (SELECT seller_id FROM product LIMIT 1)
  AND product_id = (SELECT product_id FROM product LIMIT 1)
ORDER BY category_id;
