-- EXP-083: Reorder idempotency record 크기·증가·조회 측정
-- 로컬 합성 데이터 측정 재현용. 운영 DB에서 실행하지 않는다.
--
-- 준비: postgres:18.6 일회용 container에 V1__baseline.sql을 적용한 DB에서 psql로 실행한다.
--   psql -v profile=typical -v n=1000000 -f EXP-083-reorder-idempotency-retention.sql
--   profile=typical: buyer_ref·idempotency_key가 UUID 문자열(36자)
--   profile=maxlen : 둘 다 252자(상한 256자에 가까운 값)
-- 측정 표는 order_reorder_request와 같은 컬럼·PK를 가진 임시 복제 테이블 m에서 만든다.
-- FK는 저장 크기를 바꾸지 않으므로 복제하지 않는다.
\set ON_ERROR_STOP on
\pset format unaligned
\pset fieldsep ,
DROP TABLE IF EXISTS m;
CREATE TABLE m (LIKE order_reorder_request INCLUDING ALL);
INSERT INTO m
SELECT '11111111-1111-1111-1111-111111111111'::uuid,
       CASE WHEN :'profile' = 'typical' THEN gen_random_uuid()::text ELSE repeat('b', 220) || md5((g/5)::text) END,
       CASE WHEN :'profile' = 'typical' THEN gen_random_uuid()::text ELSE repeat('k', 220) || md5(g::text) END,
       gen_random_uuid(), (g % 10 = 0), now() - (g || ' seconds')::interval
FROM generate_series(1, :n) g;
VACUUM (ANALYZE) m;

-- 1. 크기: heap, PK index, 합계, 행당 바이트
SELECT 'heap_bytes', pg_relation_size('m');
SELECT 'pk_bytes', pg_relation_size('m_pkey');
SELECT 'total_bytes', pg_total_relation_size('m');
SELECT 'total_bytes_per_row', round(pg_total_relation_size('m')::numeric / count(*), 1) FROM m;
SELECT 'avg_pg_column_size_row', round(avg(pg_column_size(m.*)), 1) FROM m;

-- 2. 조회: Repository.find와 같은 형태. PK index scan 한 번이어야 한다.
SELECT buyer_ref AS b, idempotency_key AS k INTO TEMP pick FROM m ORDER BY random() LIMIT 1;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT source_order_id, confirm_merge FROM m
WHERE seller_id = '11111111-1111-1111-1111-111111111111'
  AND buyer_ref = (SELECT b FROM pick) AND idempotency_key = (SELECT k FROM pick);

-- 3. 대안 비용: TTL 정리용 created_at index, cascade용 source_order_id index
CREATE INDEX m_created_at ON m (created_at);
SELECT 'created_at_idx_bytes', pg_relation_size('m_created_at');
CREATE INDEX m_src ON m (source_order_id);
SELECT 'source_idx_bytes', pg_relation_size('m_src');

-- 4. TTL 정리 한 배치(500행) 비용: created_at index 없음 / 있음
DROP INDEX m_created_at;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM m WHERE ctid IN (SELECT ctid FROM m WHERE created_at < now() - interval '500000 seconds' LIMIT 500);
ROLLBACK;
CREATE INDEX m_created_at ON m (created_at);
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)
DELETE FROM m WHERE ctid IN (SELECT ctid FROM m WHERE created_at < now() - interval '500000 seconds' LIMIT 500);
ROLLBACK;

-- 5. 요청당 증가: 새 typical 1,000,000행 테이블(위 1번 이후 m을 다시 만든다)에서
--    CHECKPOINT 후 단일 행 INSERT를 5,000건 실행하고 pg_total_relation_size와 WAL LSN 차이를 읽는다.
--    pgbench 스크립트(ins.sql 한 줄):
--      INSERT INTO m VALUES ('11111111-1111-1111-1111-111111111111', gen_random_uuid()::text,
--        gen_random_uuid()::text, gen_random_uuid(), false, now());
--    pgbench -n -f ins.sql -t 5000 -c 1
DROP TABLE m;
