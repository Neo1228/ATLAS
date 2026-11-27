-- V7(원장 추가 전용 트리거와 분개 균형 지연 제약 트리거)의 되돌리기. Flyway 커뮤니티판에는 자동 undo가 없다.
-- 소유 계정으로 한 트랜잭션에서 실행한다. 데이터는 건드리지 않고 트리거와 함수만 지운다.
--
--   psql -v ON_ERROR_STOP=1 -1 -f scripts/db/rollback/V7__undo_settlement_ledger_guards.sql
--
-- 마지막 줄은 이력에서 V7 행을 지워 같은 이미지가 다시 V7을 적용하게 한다. 앱을 V7 이전 이미지로 되돌릴
-- 때는 이 줄이 필요 없다(Flyway 는 이미 적용된 더 높은 버전을 무시한다. 기본 ignoreMigrationPatterns=*:future).
-- 이력 행을 지운 채 V7 이 들어 있는 이미지를 기동하면 V7 이 다시 적용된다.

DROP TRIGGER IF EXISTS settlement_entry_journal_balanced ON settlement_entry;
DROP TRIGGER IF EXISTS settlement_journal_entries_balanced ON settlement_journal;
DROP FUNCTION IF EXISTS assert_settlement_journal_balanced();

DROP TRIGGER IF EXISTS settlement_seller_allocation_no_truncate ON settlement_seller_allocation;
DROP TRIGGER IF EXISTS settlement_seller_allocation_append_only ON settlement_seller_allocation;
DROP TRIGGER IF EXISTS settlement_entry_no_truncate ON settlement_entry;
DROP TRIGGER IF EXISTS settlement_entry_append_only ON settlement_entry;
DROP TRIGGER IF EXISTS settlement_journal_no_truncate ON settlement_journal;
DROP TRIGGER IF EXISTS settlement_journal_append_only ON settlement_journal;
DROP FUNCTION IF EXISTS reject_settlement_ledger_mutation();

DELETE FROM flyway_schema_history WHERE version = '7';
