-- EXP-026: 만료 sweep 후보 조회의 상한 적용 전후 비교
-- 로컬 합성 데이터 측정 재현용. 운영 DB에서 실행하지 않는다.
--
-- 사용법:
--   docker run --rm -e POSTGRES_PASSWORD=x -e POSTGRES_DB=atlas -d --name sweep-measure postgres:18.6
--   docker exec -i sweep-measure psql -U postgres -d atlas -q < exp-026-expiry-sweep-bound.sql

CREATE TABLE sweep_t (
    order_id UUID PRIMARY KEY,
    state TEXT NOT NULL,
    checkout_expires_at TIMESTAMPTZ NOT NULL,
    order_source TEXT NOT NULL
);

-- 60,000 already-expired + 60,000 not-yet-expired rows
INSERT INTO sweep_t
SELECT gen_random_uuid(), 'RESERVED', CURRENT_TIMESTAMP - INTERVAL '10 minutes', 'CART'
FROM generate_series(1, 60000);
INSERT INTO sweep_t
SELECT gen_random_uuid(), 'RESERVED', CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'CART'
FROM generate_series(1, 60000);

-- same shape as purchase_order_expiry_idx (V3)
CREATE INDEX sweep_t_idx ON sweep_t (checkout_expires_at) WHERE state = 'RESERVED';
ANALYZE sweep_t;

-- A. unbounded: reads and sorts every expired row
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT order_id FROM sweep_t
WHERE state = 'RESERVED' AND checkout_expires_at <= CURRENT_TIMESTAMP
ORDER BY checkout_expires_at;

-- B. bounded to one sweep batch
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT order_id FROM sweep_t
WHERE state = 'RESERVED' AND checkout_expires_at <= CURRENT_TIMESTAMP
ORDER BY checkout_expires_at
LIMIT 25;
