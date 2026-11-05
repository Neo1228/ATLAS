-- Use only with the dedicated atlas_catalog_perf database.
DO $$
BEGIN
    IF current_database() <> 'atlas_catalog_perf' THEN
        RAISE EXCEPTION 'Refusing EXPLAIN: current database is %, expected atlas_catalog_perf', current_database();
    END IF;
END
$$;

EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH product_listing AS (
    SELECT p.*,
        encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
            get_byte(uuid_send(p.seller_id), 0) # 128), 8,
            get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
        encode(set_byte(set_byte(uuid_send(p.product_id), 0,
            get_byte(uuid_send(p.product_id), 0) # 128), 8,
            get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
    FROM product p
),
cursor_key AS (
    SELECT
        encode(set_byte(set_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 0,
            get_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 0) # 128), 8,
            get_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 8) # 128), 'hex') COLLATE "C"
            AS seller_sort_key,
        encode(set_byte(set_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 0,
            get_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 0) # 128), 8,
            get_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 8) # 128), 'hex') COLLATE "C"
            AS product_sort_key
)
SELECT p.product_id, p.seller_id, p.name, p.description
FROM product_listing p CROSS JOIN cursor_key c
WHERE (CAST(NULLIF(:'seller_id', '') AS uuid) IS NULL
       OR p.seller_id = CAST(NULLIF(:'seller_id', '') AS uuid))
  AND (CAST(:'keyword' AS text) = ''
       OR position(CAST(:'keyword' AS text) in lower(p.name)) > 0)
  AND (CAST(NULLIF(:'cursor_seller', '') AS uuid) IS NULL
       OR p.seller_sort_key > c.seller_sort_key
       OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
ORDER BY p.seller_sort_key, p.product_sort_key
LIMIT :fetch_limit;
