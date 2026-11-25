-- EXP-096 H10: trigger가 INSERT 한 건에 더하는 비용(맥락)과 trigger 조회의 실행 계획.
-- 시간은 공유 호스트의 값이라 판정에 쓰지 않는다. 판정은 실행 계획의 노드 종류(Index Scan 여부)로 한다.
\set ON_ERROR_STOP on
\timing off
-- 계획 확인용으로 다른 Seller 20,000명의 프로필 감사 행 200,000개를 FK 검사 없이 채운다.
SET session_replication_role = replica;
INSERT INTO seller_payout_profile_action(seller_id, profile_version, action, idempotency_key,
    request_facts_sha256, actor, reason, created_at)
SELECT s.id, 1 + (g % 10), 'PROFILE_SUBMITTED', 'bulk-' || g, repeat('0', 64), 'bulk', 'bulk', now()
FROM generate_series(1, 200000) g CROSS JOIN LATERAL (SELECT (md5((g % 20000)::text))::uuid AS id) s;
SET session_replication_role = origin;
ANALYZE seller_payout_profile_action;

SELECT seller AS seller, payout AS payout FROM exp_seed(gen_random_uuid()::text, gen_random_uuid()::text) \gset
COMMIT;

\echo '--- trigger 조회의 실행 계획 (seller_payout_profile_action 200,000행)'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT EXISTS (
    SELECT 1 FROM settlement_payout payout
    JOIN settlement_payout_dispatch_outbox outbox ON outbox.payout_id = payout.payout_id
    JOIN seller_payout_profile_action verification ON verification.seller_id = payout.seller_id
        AND verification.profile_version = outbox.profile_version AND verification.action = 'PROFILE_VERIFIED'
    WHERE payout.payout_id = :'payout' AND verification.actor = 'x');

CREATE TABLE IF NOT EXISTS exp_timing(variant text, micros double precision);
TRUNCATE exp_timing;
DO $$
DECLARE
    payout uuid := (SELECT payout_id FROM settlement_payout ORDER BY requested_at DESC LIMIT 1);
    actor text := gen_random_uuid()::text;
    started timestamptz;
    v text;
    i int;
BEGIN
    FOR i IN 1..3000 LOOP
        FOR v IN SELECT x FROM unnest(ARRAY['d0', 't']) AS x ORDER BY random() LOOP
            started := clock_timestamp();
            EXECUTE format($q$INSERT INTO exp_action_%s(payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (%L, %L, 'PAYOUT_APPROVED', %L, 'exp', now())$q$, v, payout, gen_random_uuid()::text, actor);
            INSERT INTO exp_timing VALUES (v, extract(epoch FROM clock_timestamp() - started) * 1e6);
        END LOOP;
    END LOOP;
END $$;
\echo '--- INSERT 한 건 시간(마이크로초), 변형별. 3,000건씩, 순서 무작위'
SELECT variant, count(*) AS n, round(min(micros)::numeric) AS min_us,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY micros)::numeric) AS p50_us,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY micros)::numeric) AS p95_us,
       round(max(micros)::numeric) AS max_us
FROM exp_timing GROUP BY variant ORDER BY variant;
