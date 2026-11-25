-- EXP-090 Part B: admission_ticket 크기와 tombstone 정리 batch 비용.
-- 실행: docker run --rm -d --label atlasexp=g1 -e POSTGRES_PASSWORD=x --name g1-pg postgres:18.6
--       docker exec -i g1-pg psql -U postgres -v ON_ERROR_STOP=1 < 이 파일
-- 컨테이너는 image 기본 설정(-c override 없음)이다. 값은 맥락이며 운영 규모로 일반화하지 않는다.
\timing on
\set ON_ERROR_STOP on
DROP TABLE IF EXISTS admission_ticket, purchase_order_stub;

-- V1__baseline.sql의 admission_ticket과 같은 열·제약·인덱스(CHECK 제약은 크기에 영향이 없어 생략).
CREATE TABLE admission_ticket (
    ticket_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    sequence bigint NOT NULL,
    status character varying(32) NOT NULL,
    expires_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    checkout_slot_acquired_at timestamp with time zone,
    checkout_slot_held boolean NOT NULL
);
ALTER TABLE admission_ticket ADD CONSTRAINT admission_ticket_pk PRIMARY KEY (ticket_id);
ALTER TABLE admission_ticket ADD CONSTRAINT admission_ticket_idempotency_unique UNIQUE (campaign_id, idempotency_key);
ALTER TABLE admission_ticket ADD CONSTRAINT admission_ticket_seller_sku_sequence_unique UNIQUE (seller_id, sku_id, sequence);
CREATE INDEX admission_ticket_expiry_idx ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');

-- 주문이 ticket_id를 가리키는 모양(실제는 purchase_order.ticket_id UNIQUE, FK 없음).
CREATE TABLE purchase_order_stub (order_id uuid PRIMARY KEY, ticket_id uuid UNIQUE);

-- 합성 1,000,000행: 100개 Seller-SKU, 상태 분포는 EXP-086 주 격자 평균(티켓 약 530개 중 대기 만료 약 330,
-- 슬롯 보유 뒤 만료 약 30, 시작 후 포기 등)을 거칠게 따른 가정이다. key와 buyer_ref는 36자 UUID 문자열.
-- 80%는 EXPIRED, 10% CHECKOUT_FAILED, 10% CHECKOUT_SUBMITTED. 생성 시각은 최근 60일에 고르게 분포.
INSERT INTO admission_ticket
SELECT uuidv7((-(g * 60.0 / 1000000) || ' days')::interval),
       ('00000000-0000-0000-0000-' || lpad((g % 5000)::text, 12, '0'))::uuid,
       ('00000000-0000-0000-0001-' || lpad((g % 100)::text, 12, '0'))::uuid,
       'sku-' || lpad((g % 100)::text, 6, '0'),
       gen_random_uuid()::text,
       gen_random_uuid()::text,
       g,
       CASE WHEN g % 10 < 8 THEN 'EXPIRED' WHEN g % 10 = 8 THEN 'CHECKOUT_FAILED' ELSE 'CHECKOUT_SUBMITTED' END,
       now() - (g * 60.0 / 1000000 || ' days')::interval,
       now() - (g * 60.0 / 1000000 || ' days')::interval - interval '5 minutes',
       NULL,
       false
FROM generate_series(1, 1000000) AS g;
-- 주문은 ticket의 5%(제출 10% 중 절반, sequence % 20 = 9)를 가리킨다.
INSERT INTO purchase_order_stub
SELECT gen_random_uuid(), ticket_id FROM admission_ticket WHERE status = 'CHECKOUT_SUBMITTED' AND sequence % 20 = 9;
VACUUM (ANALYZE) admission_ticket;
VACUUM (ANALYZE) purchase_order_stub;

\echo == 크기 (1,000,000행)
SELECT pg_relation_size('admission_ticket') AS heap_bytes,
       pg_indexes_size('admission_ticket') AS index_bytes,
       pg_total_relation_size('admission_ticket') AS total_bytes,
       round(pg_total_relation_size('admission_ticket') / 1000000.0, 1) AS total_bytes_per_row
FROM (SELECT 1) AS x;

\echo == 정리 후보 수(상태 EXPIRED 또는 CHECKOUT_FAILED, 주문이 가리키지 않음, 30일 초과)
SELECT count(*) FROM admission_ticket t
WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
  AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id);

\echo == A. 인덱스 없이 batch 500 삭제 계획과 시간 (ticket_id 순서, 오래된 것부터)
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, TIMING)
DELETE FROM admission_ticket
WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.ticket_id
    LIMIT 500
    FOR UPDATE SKIP LOCKED);
ROLLBACK;

\echo == C. 부분 인덱스를 추가했을 때(정리할 행이 있는 상태) (created_at, ticket_id) WHERE status IN (EXPIRED, CHECKOUT_FAILED)
CREATE INDEX admission_ticket_tombstone_idx ON admission_ticket (created_at, ticket_id)
    WHERE status IN ('EXPIRED', 'CHECKOUT_FAILED');
ANALYZE admission_ticket;
SELECT pg_size_pretty(pg_relation_size('admission_ticket_tombstone_idx')) AS tombstone_idx_size;
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, TIMING)
DELETE FROM admission_ticket
WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id
    LIMIT 500
    FOR UPDATE SKIP LOCKED);
ROLLBACK;

-- 정상 상태(정리할 행이 없음)를 재려면 후보를 먼저 지워 커밋하고 VACUUM해야 한다. 같은 트랜잭션 안에서 지우고 재면
-- 지운 행의 dead tuple까지 훑어 비용이 부풀려진다(이 스크립트의 첫 판이 그렇게 잘못 쟀다).
DELETE FROM admission_ticket t
WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
  AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id);
VACUUM (ANALYZE) admission_ticket;

\echo == D. 정상 상태(후보 0개), 부분 인덱스 있음
EXPLAIN (ANALYZE, BUFFERS, TIMING)
DELETE FROM admission_ticket
WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.created_at, t.ticket_id
    LIMIT 500
    FOR UPDATE SKIP LOCKED);

\echo == B. 정상 상태(후보 0개), 부분 인덱스 없음, ticket_id 순서
DROP INDEX admission_ticket_tombstone_idx;
ANALYZE admission_ticket;
EXPLAIN (ANALYZE, BUFFERS, TIMING)
DELETE FROM admission_ticket
WHERE ticket_id IN (
    SELECT t.ticket_id FROM admission_ticket t
    WHERE t.status IN ('EXPIRED', 'CHECKOUT_FAILED') AND t.created_at < now() - interval '30 days'
      AND NOT EXISTS (SELECT 1 FROM purchase_order_stub o WHERE o.ticket_id = t.ticket_id)
    ORDER BY t.ticket_id
    LIMIT 500
    FOR UPDATE SKIP LOCKED);
