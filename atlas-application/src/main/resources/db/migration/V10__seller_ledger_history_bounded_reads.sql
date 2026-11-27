-- ADR-0104·EXP-115: 판매자 정산서와 판매자 락 안의 잔액 읽기가 판매자 원장 이력 전체를 읽던 것을 끊는다.
--
-- 1. checkout 배분에 분개의 recorded_at을 둔다(BEFORE INSERT 트리거가 분개 행에서 채운다. 앱은 넘기지 않는다).
--    배분 인덱스를 (seller_id, journal_id)에서 (seller_id, recorded_at, journal_id)로 바꿔 정산서의 checkout 갈래가
--    기간 안의 배분만 읽게 한다(EXP-115: 100k 판매자의 30일 행 첫 페이지가 판매자 배분 18,000건 전부를 읽었다).
--
-- 기존 행 채우기는 V7 추가 전용 트리거를 이 트랜잭션 안에서만 끄고 한 번 UPDATE한다(ALTER TABLE이 배분 표에
-- ACCESS EXCLUSIVE 잠금을 잡으므로 채우는 동안 checkout 기록이 기다린다. EXP-115 데이터 배분 707,951행 기준 시간은
-- EXP-115 "V10 적용 시간"). 롤백: scripts/db/rollback/V10__undo_seller_ledger_history_bounded_reads.sql.

ALTER TABLE settlement_seller_allocation ADD COLUMN recorded_at timestamp with time zone;

-- V7 롤백 스크립트로 추가 전용 트리거를 지운 DB에서도 적용되게, 트리거가 있을 때만 끄고 다시 켠다.
DO $$
DECLARE
    guarded boolean := EXISTS (SELECT 1 FROM pg_trigger
                               WHERE tgrelid = 'settlement_seller_allocation'::regclass
                                 AND tgname = 'settlement_seller_allocation_append_only');
BEGIN
    IF guarded THEN
        ALTER TABLE settlement_seller_allocation DISABLE TRIGGER settlement_seller_allocation_append_only;
    END IF;
    UPDATE settlement_seller_allocation a
       SET recorded_at = j.recorded_at
      FROM settlement_journal j
     WHERE j.journal_id = a.journal_id;
    IF guarded THEN
        ALTER TABLE settlement_seller_allocation ENABLE TRIGGER settlement_seller_allocation_append_only;
    END IF;
END;
$$;

ALTER TABLE settlement_seller_allocation ALTER COLUMN recorded_at SET NOT NULL;

-- 배분의 recorded_at은 언제나 그 분개의 recorded_at이다. 앱이 값을 넘겨도 분개 값으로 덮는다(사본 하나, 원천은 분개).
CREATE FUNCTION fill_settlement_allocation_recorded_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    SELECT j.recorded_at INTO NEW.recorded_at FROM settlement_journal j WHERE j.journal_id = NEW.journal_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_seller_allocation_recorded_at BEFORE INSERT ON settlement_seller_allocation
    FOR EACH ROW EXECUTE FUNCTION fill_settlement_allocation_recorded_at();

CREATE INDEX settlement_seller_allocation_seller_recorded_idx
    ON settlement_seller_allocation (seller_id, recorded_at, journal_id);
DROP INDEX settlement_seller_allocation_seller_journal_idx;

-- 2. 판매자 잔액 마감(checkpoint). 커서(entry_id) 아래 항목의 판매자·계정별 합이다. 항목 기록 경로는 이 표를 건드리지
--    않고(잠금 불변), SettlementBalanceCheckpointWorker 가 쓰기 경로 밖에서 커서를 옮긴다. 잔액 읽기 =
--    마감 행 + 커서 이후 항목. 커서는 0에서 시작하므로 배포 직후에는 지금과 같은 양을 읽고, worker 가 따라잡으면
--    판매자 이력과 무관해진다(EXP-115 Ae). 마감이 멈춰도 잔액은 정확하다.
CREATE TABLE settlement_seller_balance_checkpoint (
    seller_id uuid PRIMARY KEY,
    payable_won bigint NOT NULL,
    reserve_won bigint NOT NULL,
    fee_won bigint NOT NULL
);

-- 커서가 넘을 수 있는 경계는 역할과 무관한 트랜잭션 번호로 정한다. 마감 한 번이 (그 트랜잭션 시작 시각 − 안전 지연의
-- UUIDv7 하한, 자기 xid)를 pending 으로 남기고, 다음 마감은 pg_snapshot_xmin 이 그 xid 를 넘었을 때만(그보다 먼저
-- xid를 받은 쓰기 트랜잭션이 모두 끝났을 때만) 커서를 pending 까지 옮긴다. pending 아래 entry_id 를 가진 항목은
-- pending 시각 전에 xid를 받은 트랜잭션만 쓸 수 있다(아래 entry_id 기본값). 그래서 어느 DB 역할의 늦은 커밋도
-- 커서 아래로 들어가지 않는다(pg_stat_activity 는 다른 역할 세션의 xact_start 를 보여 주지 않는다. EXP-115).
CREATE TABLE settlement_balance_checkpoint_cursor (
    singleton boolean PRIMARY KEY DEFAULT true CONSTRAINT settlement_balance_checkpoint_cursor_singleton CHECK (singleton),
    entry_id uuid NOT NULL,
    folded_at timestamp with time zone,
    pending_entry_id uuid,
    pending_xid xid8,
    CONSTRAINT settlement_balance_checkpoint_cursor_pending_pair
        CHECK ((pending_entry_id IS NULL) = (pending_xid IS NULL))
);

INSERT INTO settlement_balance_checkpoint_cursor (entry_id) VALUES ('00000000-0000-0000-0000-000000000000');

-- 항목 id 는 트랜잭션이 xid 를 받은 뒤에 시계를 읽는다. uuidv7() 기본값만 두면 트랜잭션의 첫 쓰기에서 기본값(시계)이
-- heap 삽입(xid 배정)보다 먼저 계산되므로, "pending 시각 전 id ⇒ pending xid 보다 작은 xid"가 그 틈에서 깨진다.
-- 앱 경로는 분개를 먼저 넣어 이미 xid 가 있지만, 다른 역할·도구가 항목부터 넣어도 규칙이 서게 DB가 보장한다.
CREATE FUNCTION settlement_entry_next_id() RETURNS uuid
    LANGUAGE sql VOLATILE
    AS $$ SELECT CASE WHEN pg_current_xact_id() IS NOT NULL THEN uuidv7() END $$;

ALTER TABLE settlement_entry ALTER COLUMN entry_id SET DEFAULT settlement_entry_next_id();
