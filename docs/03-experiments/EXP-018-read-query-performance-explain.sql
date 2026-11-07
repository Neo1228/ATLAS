-- EXP-018: query-plan capture only. No schema/database is created or assumed here.
-- Run only against a disposable, representative PostgreSQL database with the
-- application's current product, product_category, product_channel_availability,
-- settlement_journal, and settlement_seller_allocation tables and columns.
-- Bind psql variables below before execution; empty UUID strings mean NULL.
-- ANALYZE executes each SELECT. These statements are read-only; do not use on a
-- production system. No result or performance claim is implied by this artifact.

-- Required psql variables: seller_id, category_id, keyword, cursor_seller,
-- cursor_product, page_limit, management_scope (true/false).
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH RECURSIVE product_listing AS (
  SELECT p.*,
    encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
      get_byte(uuid_send(p.seller_id), 0) # 128), 8,
      get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
      get_byte(uuid_send(p.product_id), 0) # 128), 8,
      get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
  FROM product p
),
category_tree(category_id) AS (
  SELECT CAST(NULLIF(:'category_id', '') AS uuid)
  WHERE CAST(NULLIF(:'category_id', '') AS uuid) IS NOT NULL
  UNION
  SELECT child.category_id
  FROM product_category child
  JOIN category_tree parent ON child.parent_category_id = parent.category_id
  WHERE child.seller_id = CAST(NULLIF(:'seller_id', '') AS uuid)
),
cursor_key AS (
  SELECT
    encode(set_byte(set_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 0,
      get_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 0) # 128), 8,
      get_byte(uuid_send(CAST(NULLIF(:'cursor_seller', '') AS uuid)), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
    encode(set_byte(set_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 0,
      get_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 0) # 128), 8,
      get_byte(uuid_send(CAST(NULLIF(:'cursor_product', '') AS uuid)), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
)
SELECT p.product_id, p.seller_id, p.category_id, p.name, p.description, p.status
FROM product_listing p CROSS JOIN cursor_key c
WHERE (CAST(NULLIF(:'seller_id', '') AS uuid) IS NULL OR p.seller_id = CAST(NULLIF(:'seller_id', '') AS uuid))
  AND (CAST(:'management_scope' AS boolean) OR (p.status = 'ACTIVE'
    AND EXISTS (SELECT 1 FROM product_channel_availability ca
      WHERE ca.product_id = p.product_id AND ca.sales_channel = 'ONLINE_STORE' AND ca.available = TRUE)))
  AND (CAST(NULLIF(:'category_id', '') AS uuid) IS NULL
    OR p.category_id IN (SELECT category_id FROM category_tree))
  AND (CAST(:'keyword' AS text) = '' OR position(CAST(:'keyword' AS text) in lower(p.name)) > 0)
  AND (CAST(NULLIF(:'cursor_seller', '') AS uuid) IS NULL OR p.seller_sort_key > c.seller_sort_key
    OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
ORDER BY p.seller_sort_key, p.product_sort_key
LIMIT :'page_limit';

-- Settlement totals bind variables: statement_seller_id, from_ts, to_ts.
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH statement_amounts AS (
  SELECT j.gross_amount_won AS amount, FALSE AS is_refund
  FROM settlement_journal j
  WHERE j.seller_id = CAST(:'statement_seller_id' AS uuid)
    AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
  UNION ALL
  SELECT a.amount_won, FALSE
  FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
  WHERE a.seller_id = CAST(:'statement_seller_id' AS uuid) AND j.checkout_id IS NOT NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
  UNION ALL
  SELECT j.gross_amount_won, TRUE
  FROM settlement_journal j
  WHERE j.seller_id = CAST(:'statement_seller_id' AS uuid) AND j.source_refund_id IS NOT NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
)
SELECT COALESCE(SUM(amount) FILTER (WHERE NOT is_refund), 0) AS captured,
       COALESCE(-SUM(amount) FILTER (WHERE is_refund), 0) AS reversal,
       COALESCE(SUM(CASE WHEN is_refund THEN -amount ELSE amount END), 0) AS net
FROM statement_amounts;

-- Settlement page bind variables: seller_id, from_ts, to_ts, has_after,
-- after_ts (nullable), after_id (nullable UUID), page_limit.
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH rows AS (
  SELECT j.journal_id, j.source_order_id AS order_id, j.source_attempt_id AS source_id,
    'ORDER_CAPTURE' AS type, j.recorded_at, j.gross_amount_won AS amount
  FROM settlement_journal j
  WHERE j.seller_id = CAST(:'statement_seller_id' AS uuid) AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
  UNION ALL
  SELECT j.journal_id, a.order_id, j.checkout_id, 'CHECKOUT_ALLOCATION', j.recorded_at, a.amount_won
  FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
  WHERE a.seller_id = CAST(:'statement_seller_id' AS uuid) AND j.checkout_id IS NOT NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
  UNION ALL
  SELECT j.journal_id, j.source_order_id, j.source_refund_id, 'REFUND_REVERSAL', j.recorded_at,
    -j.gross_amount_won
  FROM settlement_journal j
  WHERE j.seller_id = CAST(:'statement_seller_id' AS uuid) AND j.source_refund_id IS NOT NULL
    AND j.recorded_at >= CAST(:'from_ts' AS timestamptz) AND j.recorded_at < CAST(:'to_ts' AS timestamptz)
)
SELECT journal_id, order_id, source_id, type, recorded_at, amount
FROM rows
WHERE (NOT CAST(:'has_after' AS boolean)
  OR (recorded_at, journal_id) > (CAST(NULLIF(:'after_ts', '') AS timestamptz), CAST(NULLIF(:'after_id', '') AS uuid)))
ORDER BY recorded_at ASC, journal_id ASC
LIMIT :'page_limit';
