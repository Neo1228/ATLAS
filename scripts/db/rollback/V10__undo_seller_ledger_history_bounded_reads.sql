-- V10(배분 recorded_at·배분 기간 인덱스, 판매자 잔액 마감 표 2개)의 되돌리기. Flyway 커뮤니티판에는 자동 undo가 없다.
-- V10 이전 이미지로 앱을 먼저 되돌린 뒤(새 코드는 배분 recorded_at 을 읽는다) 소유 계정으로 한 트랜잭션에서 실행한다.
--
--   psql -v ON_ERROR_STOP=1 -1 -f scripts/db/rollback/V10__undo_seller_ledger_history_bounded_reads.sql
--
-- 원장 행(분개·항목·배분의 금액)은 건드리지 않는다. 배분에 더한 사본 열 recorded_at 과 원장에서 다시 만들 수 있는
-- 마감 표만 지운다. 마감 worker 는 앱을 되돌리면 사라진다(atlas.settlement.balance-checkpoint.enabled=false 로도 끈다).
-- 마지막 줄은 이력에서 V10 행을 지워 같은 이미지가 다시 V10 을 적용하게 한다(V7 롤백과 같은 규칙).

DROP TRIGGER IF EXISTS settlement_seller_allocation_recorded_at ON settlement_seller_allocation;
DROP FUNCTION IF EXISTS fill_settlement_allocation_recorded_at();
CREATE INDEX IF NOT EXISTS settlement_seller_allocation_seller_journal_idx
    ON settlement_seller_allocation (seller_id, journal_id);
DROP INDEX IF EXISTS settlement_seller_allocation_seller_recorded_idx;
ALTER TABLE settlement_seller_allocation DROP COLUMN IF EXISTS recorded_at;

DROP TABLE IF EXISTS settlement_balance_checkpoint_cursor;
DROP TABLE IF EXISTS settlement_seller_balance_checkpoint;

DELETE FROM flyway_schema_history WHERE version = '10';
