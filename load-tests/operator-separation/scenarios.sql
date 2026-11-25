-- EXP-096 E1: 승인자 ≠ 검증자 규칙의 강제 수단 비교(SQL 수준).
-- 실행: load-tests/operator-separation/run.sh (컨테이너를 만들고 migration을 적용한 뒤 이 파일을 실행한다).
--
-- 변형(variant)별로 감사 행 표를 따로 만들어 같은 고정 데이터(실제 V1 표)를 읽게 한다.
--   d0 규칙 없음(음성 대조)
--   s  service 검사: 버전에 묶인 검증자를 읽고(별도 문장), 다르면 INSERT
--   c  조건부 SQL: INSERT ... SELECT ... WHERE NOT EXISTS (버전에 묶인 검증자 = actor)
--   t  DB trigger: V5가 만든 enforce_payout_operator_separation() 그대로
--   n  "현재 profile 행의 verified_by" 를 읽는 trigger(음성 대조: 규칙이 읽을 값을 잘못 고른 경우)
--
-- 시나리오(scenario)
--   x0 정당한 승인(검증자와 다른 사람)         기대: 전 변형 통과(규칙이 과하게 거절하지 않음)
--   x1 같은 사람(검증자 = 승인자)              기대: d0만 통과
--   x2 승인 트랜잭션 도중 프로필이 다음 버전으로 넘어감. 승인자는 outbox에 묶인 버전의 검증자  기대: d0, n 통과(위반)
--   x3 규칙을 모르는 경로의 직접 INSERT(검증자 = 승인자)  기대: d0, s, c 통과(위반), t, n 거절
-- 승인 트랜잭션은 dblink 연결 a, 프로필 이동은 연결 b이고 한 세션 안에서 순서를 결정적으로 고정한다.
\set ON_ERROR_STOP on
\timing off
CREATE EXTENSION IF NOT EXISTS dblink;
SELECT dblink_connect('a', format('dbname=%s user=%s', current_database(), current_user));
SELECT dblink_connect('b', format('dbname=%s user=%s', current_database(), current_user));

DROP TABLE IF EXISTS exp_result;
CREATE TABLE exp_result (
    scenario text NOT NULL, variant text NOT NULL, accepted int NOT NULL DEFAULT 0,
    rejected_by_rule int NOT NULL DEFAULT 0, other_error int NOT NULL DEFAULT 0, n int NOT NULL DEFAULT 0,
    PRIMARY KEY (scenario, variant));

DO $$
DECLARE v text;
BEGIN
    FOREACH v IN ARRAY ARRAY['d0', 's', 'c', 't', 'n'] LOOP
        EXECUTE format('DROP TABLE IF EXISTS exp_action_%s', v);
        EXECUTE format('CREATE TABLE exp_action_%s (LIKE settlement_payout_operator_action INCLUDING DEFAULTS)', v);
    END LOOP;
END $$;

CREATE TRIGGER exp_t BEFORE INSERT ON exp_action_t
    FOR EACH ROW EXECUTE FUNCTION enforce_payout_operator_separation();

CREATE OR REPLACE FUNCTION exp_enforce_current_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.actor !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN RETURN NEW; END IF;
    IF NEW.action IN ('PAYOUT_APPROVED', 'PAYOUT_APPROVAL_REPLAYED') AND EXISTS (
        SELECT 1 FROM settlement_payout payout
        JOIN seller_payout_profile profile ON profile.seller_id = payout.seller_id
        WHERE payout.payout_id = NEW.payout_id AND profile.verified_by = NEW.actor) THEN
        RAISE EXCEPTION 'exp_n_separation_of_duties' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exp_n BEFORE INSERT ON exp_action_n
    FOR EACH ROW EXECUTE FUNCTION exp_enforce_current_row();

-- 한 시도를 한 변형에서 실행하고 'accepted' / 'rejected' / 'error' 중 하나를 돌려준다.
-- client_path=true 이면 s, c 변형은 자기 검사를 거치고, false 이면 규칙을 모르는 경로의 직접 INSERT 다.
CREATE OR REPLACE FUNCTION exp_try(variant text, payout uuid, actor text, client_path boolean) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    bound_match text := format($q$SELECT EXISTS (SELECT 1 FROM settlement_payout p
        JOIN settlement_payout_dispatch_outbox o ON o.payout_id = p.payout_id
        JOIN seller_payout_profile_action v ON v.seller_id = p.seller_id AND v.profile_version = o.profile_version
            AND v.action = 'PROFILE_VERIFIED'
        WHERE p.payout_id = %L AND v.actor = %L)$q$, payout, actor);
    plain text := format($q$INSERT INTO exp_action_%s(payout_id, idempotency_key, action, actor, reason, created_at)
        VALUES (%L, %L, 'PAYOUT_APPROVED', %L, 'exp', now())$q$, variant, payout, gen_random_uuid()::text, actor);
    conditional text := format($q$INSERT INTO exp_action_c(payout_id, idempotency_key, action, actor, reason, created_at)
        SELECT %L, %L, 'PAYOUT_APPROVED', %L, 'exp', now() WHERE NOT (%s)$q$, payout, gen_random_uuid()::text, actor,
        regexp_replace(bound_match, '^SELECT ', ''));
    matched boolean;
    status text;
BEGIN
    IF client_path AND variant = 's' THEN
        SELECT m INTO matched FROM dblink('a', bound_match) AS t(m boolean);
        IF matched THEN RETURN 'rejected'; END IF;
        PERFORM dblink_exec('a', plain);
    ELSIF client_path AND variant = 'c' THEN
        status := dblink_exec('a', conditional);
        IF status = 'INSERT 0 0' THEN RETURN 'rejected'; END IF;
    ELSE
        PERFORM dblink_exec('a', plain);
    END IF;
    RETURN 'accepted';
EXCEPTION WHEN OTHERS THEN
    IF SQLERRM LIKE '%separation_of_duties%' THEN RETURN 'rejected'; END IF;
    RAISE NOTICE 'exp_try other error (%): %', variant, SQLERRM;
    RETURN 'error';
END $$;

-- n회 반복. 시도마다 새 데이터를 쓰고 변형 실행 순서를 무작위로 섞는다.
CREATE OR REPLACE PROCEDURE exp_run(which text, n int) LANGUAGE plpgsql AS $$
DECLARE
    a_id text := gen_random_uuid()::text;
    b_id text := gen_random_uuid()::text;
    c_id text := gen_random_uuid()::text;
    v text;
    s record;
    outcome text;
    i int;
BEGIN
    FOR i IN 1..n LOOP
        FOR v IN SELECT x FROM unnest(ARRAY['d0', 's', 'c', 't', 'n']) AS x ORDER BY random() LOOP
            IF which = 'x0' THEN
                SELECT * INTO s FROM exp_seed(a_id, a_id);
            ELSIF which = 'x1' THEN
                SELECT * INTO s FROM exp_seed(a_id, a_id);
            ELSIF which = 'x2' THEN
                SELECT * INTO s FROM exp_seed(b_id, b_id);
            ELSE
                SELECT * INTO s FROM exp_seed(b_id, b_id);
            END IF;
            COMMIT;
            PERFORM dblink_exec('a', 'BEGIN');
            IF which = 'x2' THEN
                -- 승인 트랜잭션이 프로필을 읽은 뒤, 다른 연결이 프로필을 v4(PENDING_REVIEW) → v5(ACTIVE, 검증자 a)로 넘긴다.
                PERFORM * FROM dblink('a', format('SELECT verified_by FROM seller_payout_profile WHERE seller_id = %L', s.seller))
                    AS t(verified_by text);
                PERFORM dblink_exec('b', format($q$UPDATE seller_payout_profile SET status = 'PENDING_REVIEW',
                    profile_version = 4, verified_at = NULL, verified_by = NULL WHERE seller_id = %L$q$, s.seller));
                PERFORM dblink_exec('b', format($q$UPDATE seller_payout_profile SET status = 'ACTIVE', profile_version = 5,
                    verified_at = now(), verified_by = %L WHERE seller_id = %L$q$, a_id, s.seller));
                PERFORM dblink_exec('b', format($q$INSERT INTO seller_payout_profile_action(seller_id, profile_version, action,
                    idempotency_key, request_facts_sha256, actor, reason, created_at)
                    VALUES (%L, 5, 'PROFILE_VERIFIED', 'v5-' || %L, repeat('0', 64), %L, 'case', now())$q$,
                    s.seller, s.seller, a_id));
            END IF;
            outcome := exp_try(v, s.payout,
                CASE which WHEN 'x0' THEN c_id WHEN 'x1' THEN a_id ELSE b_id END,
                which <> 'x3');
            PERFORM dblink_exec('a', CASE outcome WHEN 'accepted' THEN 'COMMIT' ELSE 'ROLLBACK' END);
            INSERT INTO exp_result(scenario, variant, n, accepted, rejected_by_rule, other_error)
                VALUES (which, v, 1, (outcome = 'accepted')::int, (outcome = 'rejected')::int, (outcome = 'error')::int)
                ON CONFLICT (scenario, variant) DO UPDATE SET n = exp_result.n + 1,
                    accepted = exp_result.accepted + EXCLUDED.accepted,
                    rejected_by_rule = exp_result.rejected_by_rule + EXCLUDED.rejected_by_rule,
                    other_error = exp_result.other_error + EXCLUDED.other_error;
            COMMIT;
        END LOOP;
    END LOOP;
END $$;
