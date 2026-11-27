-- V11(감사·이벤트 테이블 추가 전용 트리거)의 되돌리기. Flyway 커뮤니티판에는 자동 undo가 없다.
-- 소유 계정으로 한 트랜잭션에서 실행한다. 데이터는 건드리지 않고 V11이 만든 트리거와 함수만 지운다.
-- V1의 inventory_transfer·paypal_webhook_retry_operator_audit 행 가드와 그 함수는 V11 이전부터 있으므로 남긴다.
--
--   psql -v ON_ERROR_STOP=1 -1 -f scripts/db/rollback/V11__undo_audit_append_only_guards.sql
--
-- 마지막 줄은 이력에서 V11 행을 지워 같은 이미지가 다시 V11을 적용하게 한다. 앱을 V11 이전 이미지로 되돌릴
-- 때는 이 줄이 필요 없다(Flyway 는 이미 적용된 더 높은 버전을 무시한다. 기본 ignoreMigrationPatterns=*:future).
-- runtime-role.sql 로 회수한 UPDATE·DELETE 권한은 되돌리지 않는다(앱은 이 테이블들을 고치지 않는다, EXP-116).

DROP TRIGGER IF EXISTS paypal_webhook_retry_operator_audit_no_truncate ON paypal_webhook_retry_operator_audit;
DROP TRIGGER IF EXISTS inventory_transfer_no_truncate ON inventory_transfer;

DROP TRIGGER IF EXISTS order_inventory_event_no_truncate ON order_inventory_event;
DROP TRIGGER IF EXISTS order_inventory_event_append_only ON order_inventory_event;
DROP TRIGGER IF EXISTS settlement_payout_operator_action_no_truncate ON settlement_payout_operator_action;
DROP TRIGGER IF EXISTS settlement_payout_operator_action_append_only ON settlement_payout_operator_action;
DROP TRIGGER IF EXISTS seller_payout_profile_action_no_truncate ON seller_payout_profile_action;
DROP TRIGGER IF EXISTS seller_payout_profile_action_append_only ON seller_payout_profile_action;
DROP TRIGGER IF EXISTS payment_recovery_operator_action_no_truncate ON payment_recovery_operator_action;
DROP TRIGGER IF EXISTS payment_recovery_operator_action_append_only ON payment_recovery_operator_action;
DROP TRIGGER IF EXISTS account_suspension_action_no_truncate ON account_suspension_action;
DROP TRIGGER IF EXISTS account_suspension_action_append_only ON account_suspension_action;
DROP TRIGGER IF EXISTS inventory_capacity_adjustment_no_truncate ON inventory_capacity_adjustment;
DROP TRIGGER IF EXISTS inventory_capacity_adjustment_append_only ON inventory_capacity_adjustment;
DROP FUNCTION IF EXISTS reject_append_only_mutation();

DELETE FROM flyway_schema_history WHERE version = '11';
