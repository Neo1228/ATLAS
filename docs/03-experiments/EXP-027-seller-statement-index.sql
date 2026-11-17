-- EXP-027: seller 정산 명세 index 적용 전후 비교
-- 로컬 합성 데이터 측정 재현용. 운영 DB에서 실행하지 않는다.
--
-- 사용법:
--   docker run --rm -e POSTGRES_PASSWORD=x -e POSTGRES_DB=atlas -d --name stmt-measure postgres:18.6
--   docker exec -i stmt-measure psql -U postgres -d atlas -q < EXP-027-seller-statement-index.sql

CREATE TABLE seller (seller_id uuid primary key);
CREATE TABLE settlement_journal (
  journal_id uuid primary key,
  source_order_id uuid not null,
  source_attempt_id uuid,
  source_refund_id uuid,
  checkout_id uuid,
  seller_id uuid,
  currency char(3) not null default 'KRW',
  gross_amount_won bigint not null,
  recorded_at timestamptz not null
);
CREATE TABLE settlement_seller_allocation (
  journal_id uuid not null references settlement_journal(journal_id),
  order_id uuid not null,
  seller_id uuid not null,
  amount_won bigint not null,
  allocation_ordinal integer not null,
  primary key (journal_id, order_id),
  unique (journal_id, seller_id)
);

INSERT INTO seller SELECT gen_random_uuid() FROM generate_series(1,200);

-- 400k legacy order captures spread over 200 sellers and 2 years
INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
       (SELECT seller_id FROM seller ORDER BY seller_id LIMIT 1 OFFSET (g % 200)),
       10000, CURRENT_TIMESTAMP - (g || ' minutes')::interval
FROM generate_series(1,400000) g;

-- 100k checkout journals with 1-3 allocations each
INSERT INTO settlement_journal (journal_id, source_order_id, checkout_id, gross_amount_won, recorded_at)
SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 30000,
       CURRENT_TIMESTAMP - (g || ' minutes')::interval
FROM generate_series(1,100000) g;

INSERT INTO settlement_seller_allocation (journal_id, order_id, seller_id, amount_won, allocation_ordinal)
SELECT j.journal_id, gen_random_uuid(), s.seller_id, 10000, 1
FROM settlement_journal j
CROSS JOIN LATERAL (SELECT seller_id FROM seller ORDER BY seller_id LIMIT 1 OFFSET (abs(hashtext(j.journal_id::text)) % 200)) s
WHERE j.checkout_id IS NOT NULL;

-- 50k refund reverses
INSERT INTO settlement_journal (journal_id, source_order_id, source_refund_id, seller_id, gross_amount_won, recorded_at)
SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
       (SELECT seller_id FROM seller ORDER BY seller_id LIMIT 1 OFFSET (g % 200)),
       5000, CURRENT_TIMESTAMP - (g || ' minutes')::interval
FROM generate_series(1,50000) g;

ANALYZE;

-- ---
-- 측정: 아래 query를 index 적용 전/후로 각각 실행한다.
-- V39 이전 상태 재현 (V28 index만 존재):
--   CREATE INDEX settlement_seller_allocation_seller_journal_idx
--       ON settlement_seller_allocation (seller_id, journal_id);
--   ANALYZE settlement_seller_allocation;
--   → 이 상태에서 아래 query를 먼저 측정한다.
--
-- V39 적용:
--   CREATE INDEX settlement_journal_seller_recorded_idx
--       ON settlement_journal (seller_id, recorded_at, journal_id);
--   ANALYZE settlement_journal;
--
-- seller 1명 선택:
--   SELECT seller_id FROM seller ORDER BY seller_id LIMIT 1;
--
-- EXPLAIN (ANALYZE, BUFFERS)
-- SELECT COALESCE(SUM(amount) FILTER (WHERE NOT refund), 0) AS captured,
--        COALESCE(-SUM(amount) FILTER (WHERE refund), 0) AS reversal,
--        COALESCE(SUM(CASE WHEN refund THEN -amount ELSE amount END), 0) AS net
-- FROM (
--   SELECT j.gross_amount_won AS amount, FALSE AS refund FROM settlement_journal j
--    WHERE j.seller_id = :seller AND j.source_refund_id IS NULL AND j.checkout_id IS NULL
--      AND j.recorded_at >= CURRENT_TIMESTAMP - interval '30 days' AND j.recorded_at < CURRENT_TIMESTAMP
--   UNION ALL
--   SELECT a.amount_won, FALSE FROM settlement_journal j JOIN settlement_seller_allocation a USING (journal_id)
--    WHERE a.seller_id = :seller AND j.checkout_id IS NOT NULL
--      AND j.recorded_at >= CURRENT_TIMESTAMP - interval '30 days' AND j.recorded_at < CURRENT_TIMESTAMP
--   UNION ALL
--   SELECT j.gross_amount_won, TRUE FROM settlement_journal j
--    WHERE j.seller_id = :seller AND j.source_refund_id IS NOT NULL
--      AND j.recorded_at >= CURRENT_TIMESTAMP - interval '30 days' AND j.recorded_at < CURRENT_TIMESTAMP
-- ) rows;
