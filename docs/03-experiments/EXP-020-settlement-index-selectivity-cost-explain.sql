-- EXP-020 read-only plan capture; mirrors SettlementJournalRepository SQL.
-- Run only against disposable atlas_query_evidence.
-- Required psql variables: statement_seller_id, from_ts, to_ts.
\set ON_ERROR_STOP on
\set page_limit 100
\set has_after false
\set after_ts ''
\set after_id ''

-- Totals: direct capture + checkout Seller allocations + refund reversal.
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH statement_amounts AS (
    SELECT j.gross_amount_won AS amount, FALSE AS is_refund
    FROM settlement_journal j
    WHERE j.seller_id = :'statement_seller_id'::uuid
      AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
    UNION ALL
    SELECT a.amount_won, FALSE
    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
    WHERE a.seller_id = :'statement_seller_id'::uuid AND j.checkout_id IS NOT NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
    UNION ALL
    SELECT j.gross_amount_won, TRUE
    FROM settlement_journal j
    WHERE j.seller_id = :'statement_seller_id'::uuid AND j.source_refund_id IS NOT NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
)
SELECT COALESCE(SUM(amount) FILTER (WHERE NOT is_refund), 0) AS captured,
       COALESCE(-SUM(amount) FILTER (WHERE is_refund), 0) AS reversal,
       COALESCE(SUM(CASE WHEN is_refund THEN -amount ELSE amount END), 0) AS net
FROM statement_amounts;

-- First keyset page; same cursor tuple, stable ordering, and limit as the API.
EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)
WITH rows AS (
    SELECT j.journal_id, j.source_order_id AS order_id, j.source_attempt_id AS source_id,
           'ORDER_CAPTURE' AS type, j.recorded_at, j.gross_amount_won AS amount
    FROM settlement_journal j
    WHERE j.seller_id = :'statement_seller_id'::uuid
      AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
    UNION ALL
    SELECT j.journal_id, a.order_id, j.checkout_id, 'CHECKOUT_ALLOCATION', j.recorded_at, a.amount_won
    FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
    WHERE a.seller_id = :'statement_seller_id'::uuid AND j.checkout_id IS NOT NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
    UNION ALL
    SELECT j.journal_id, j.source_order_id, j.source_refund_id, 'REFUND_REVERSAL', j.recorded_at,
           -j.gross_amount_won
    FROM settlement_journal j
    WHERE j.seller_id = :'statement_seller_id'::uuid AND j.source_refund_id IS NOT NULL
      AND j.recorded_at >= :'from_ts'::timestamptz AND j.recorded_at < :'to_ts'::timestamptz
)
SELECT journal_id, order_id, source_id, type, recorded_at, amount
FROM rows
WHERE (NOT :'has_after'::boolean
    OR (recorded_at, journal_id) > (NULLIF(:'after_ts', '')::timestamptz, NULLIF(:'after_id', '')::uuid))
ORDER BY recorded_at ASC, journal_id ASC
LIMIT :page_limit;
