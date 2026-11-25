-- EXP-092 재현: 정리 문장이 읽는 buffer가 표 크기와 "영영 못 지우는 오래된 행" 수에 따라 어떻게 변하는가.
-- 실행(컨테이너에는 label을 붙이고 끝나면 지운다):
--   docker run --rm -d --label atlasexp=h2 -e POSTGRES_PASSWORD=x --name h2-pg postgres:18.6
--   for f in atlas-application/src/main/resources/db/migration/V[123]__*.sql; do
--     docker exec -i h2-pg psql -U postgres -q -f - < "$f"; done      # V4는 아래에서 직접 적용한다
--   docker exec -i h2-pg psql -U postgres -v ON_ERROR_STOP=1 -v n=1000000 \
--     < docs/03-experiments/EXP-092-admission-tombstone-purge.sql
-- 한 번 실행에 후보 상태(A1)와 정상 상태(A2)의 EXPLAIN을 인덱스 없음(NI) 대 V4의 인덱스(MARK)로 낸다.
SET session_replication_role = replica;  -- 합성 데이터는 campaign·seller_sku 행이 없으므로 FK 검사를 끈다
SET jit = off;
-- 분포(가정): 90일에 고르게 생성, 80% EXPIRED, 10% CHECKOUT_FAILED, 10% CHECKOUT_SUBMITTED.
INSERT INTO admission_ticket (ticket_id, campaign_id, seller_id, sku_id, buyer_ref, idempotency_key,
    sequence, status, expires_at, created_at, checkout_slot_acquired_at, checkout_slot_held)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
    'sku-' || (g % 200), 'buyer-' || g, 'key-' || g, g,
    CASE WHEN g % 10 < 8 THEN 'EXPIRED' WHEN g % 10 = 8 THEN 'CHECKOUT_FAILED' ELSE 'CHECKOUT_SUBMITTED' END,
    NULL, now() - interval '90 days' + (g::float8 / :n) * interval '90 days', NULL, false
FROM generate_series(1, :n) AS g;
-- 주문이 가리키는 ticket: CHECKOUT_SUBMITTED 전부 + EXPIRED 중 5%.
INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref, state, checkout_expires_at, created_at, updated_at)
SELECT gen_random_uuid(), seller_id, campaign_id, ticket_id, buyer_ref, 'EXPIRED', created_at, created_at, created_at
FROM admission_ticket WHERE status = 'CHECKOUT_SUBMITTED' OR (status = 'EXPIRED' AND sequence % 20 = 0);
-- V4와 같은 열·backfill
ALTER TABLE admission_ticket ADD COLUMN checkout_started_at timestamptz;
UPDATE admission_ticket t SET checkout_started_at = t.created_at FROM purchase_order o WHERE o.ticket_id = t.ticket_id;
VACUUM (ANALYZE) admission_ticket;
VACUUM (ANALYZE) purchase_order;

\echo '== A1 후보가 많은 상태, 인덱스 없음 (NI)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM admission_ticket WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.checkout_started_at IS NULL
      AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id LIMIT 500 FOR UPDATE OF t SKIP LOCKED);
ROLLBACK;

CREATE INDEX admission_ticket_tombstone_idx ON admission_ticket (created_at, ticket_id)
    WHERE status IN ('EXPIRED', 'CHECKOUT_FAILED') AND checkout_started_at IS NULL;   -- V4
VACUUM (ANALYZE) admission_ticket;
\echo '== A1 후보가 많은 상태, V4 인덱스 (MARK)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM admission_ticket WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.checkout_started_at IS NULL
      AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id LIMIT 500 FOR UPDATE OF t SKIP LOCKED);
ROLLBACK;

-- 정상 상태: 후보를 비운다. 남는 오래된 행은 주문이 가리키는 것(checkout_started_at 있음)뿐이다.
DELETE FROM admission_ticket WHERE status IN ('EXPIRED', 'CHECKOUT_FAILED') AND checkout_started_at IS NULL
    AND created_at < now() - interval '30 days';
VACUUM (ANALYZE) admission_ticket;
\echo '== A2 정상 상태(지울 행 없음), V4 인덱스 (MARK)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM admission_ticket WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.checkout_started_at IS NULL
      AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id LIMIT 500 FOR UPDATE OF t SKIP LOCKED);
ROLLBACK;

-- H2 음성 대조군: checkout_started_at 조건이 없는 인덱스는 영영 못 지우는 행(주문이 가리키는 오래된 행)을 매번 훑는다.
DROP INDEX admission_ticket_tombstone_idx;
CREATE INDEX admission_ticket_tombstone_idx ON admission_ticket (created_at, ticket_id)
    WHERE status IN ('EXPIRED', 'CHECKOUT_FAILED');
VACUUM (ANALYZE) admission_ticket;
\echo '== A2 정상 상태, checkout_started_at 조건이 없는 인덱스 (NOMARK): 보호된 행 수에 비례해 읽는다'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM admission_ticket WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id LIMIT 500 FOR UPDATE OF t SKIP LOCKED);
ROLLBACK;
