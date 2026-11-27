-- EXP-116, ADR-0105: 문서가 추가 전용이라고 쓰고 운영 코드가 UPDATE·DELETE·TRUNCATE하지 않는 감사·이벤트
-- 테이블을 V7과 같은 방식으로 DB가 지킨다(행 단위 UPDATE·DELETE 거절 + 문장 단위 TRUNCATE 거절).
--
-- 1. 가드가 없던 6개: 재고 조정 감사, 운영자 조치 4개, 예약 이벤트 로그. 새 함수 하나를 함께 쓴다.
-- 2. 행 가드는 있고 TRUNCATE 가드만 없던 2개(inventory_transfer, paypal_webhook_retry_operator_audit): V1의
--    같은 거절 함수로 TRUNCATE 트리거만 더한다.
--
-- 운영 코드의 사용처 수(0건)는 EXP-116 표에 있다. INSERT 경로에는 발동하지 않는다. 기존 행은 검사하지 않는다.
-- 롤백은 scripts/db/rollback/V11__undo_audit_append_only_guards.sql, 절차는 docs/operations-guide.md 의
-- "감사·이벤트 테이블 추가 전용 가드" 절이다. 소유 계정은 ALTER TABLE ... DISABLE TRIGGER 로 우회할 수 있으므로
-- 런타임 역할을 따로 두는 scripts/db/runtime-role.sql 이 같은 테이블의 UPDATE·DELETE 권한을 회수한다.

CREATE FUNCTION reject_append_only_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '%_is_append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER inventory_capacity_adjustment_append_only BEFORE DELETE OR UPDATE ON inventory_capacity_adjustment
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER inventory_capacity_adjustment_no_truncate BEFORE TRUNCATE ON inventory_capacity_adjustment
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER account_suspension_action_append_only BEFORE DELETE OR UPDATE ON account_suspension_action
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER account_suspension_action_no_truncate BEFORE TRUNCATE ON account_suspension_action
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER payment_recovery_operator_action_append_only BEFORE DELETE OR UPDATE ON payment_recovery_operator_action
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER payment_recovery_operator_action_no_truncate BEFORE TRUNCATE ON payment_recovery_operator_action
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER seller_payout_profile_action_append_only BEFORE DELETE OR UPDATE ON seller_payout_profile_action
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER seller_payout_profile_action_no_truncate BEFORE TRUNCATE ON seller_payout_profile_action
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER settlement_payout_operator_action_append_only BEFORE DELETE OR UPDATE ON settlement_payout_operator_action
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER settlement_payout_operator_action_no_truncate BEFORE TRUNCATE ON settlement_payout_operator_action
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER order_inventory_event_append_only BEFORE DELETE OR UPDATE ON order_inventory_event
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER order_inventory_event_no_truncate BEFORE TRUNCATE ON order_inventory_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER inventory_transfer_no_truncate BEFORE TRUNCATE ON inventory_transfer
    FOR EACH STATEMENT EXECUTE FUNCTION reject_inventory_transfer_mutation();
CREATE TRIGGER paypal_webhook_retry_operator_audit_no_truncate BEFORE TRUNCATE ON paypal_webhook_retry_operator_audit
    FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_webhook_retry_audit_mutation();
