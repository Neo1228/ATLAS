-- EXP-111, ADR-0100: 원장(settlement_journal, settlement_entry, settlement_seller_allocation)을 DB가 지킨다.
--
-- 1. 추가 전용: UPDATE, DELETE, TRUNCATE를 거절한다(V1의 PayPal 보고서 reject 트리거와 같은 패턴).
-- 2. 분개 균형: 커밋 시점에 분개마다 항목이 하나 이상 있고 차변 합 = 대변 합이어야 한다(지연 제약 트리거).
--
-- 기존 행은 검사하지 않는다(트리거는 새 쓰기에만 발동). 적용 전 점검, 롤백, 정정 절차는
-- docs/operations-guide.md 의 "원장 무결성" 절과 scripts/db/rollback/V7__undo_settlement_ledger_guards.sql 이다.
-- 소유 계정은 ALTER TABLE ... DISABLE TRIGGER 로 우회할 수 있으므로 런타임 역할을 따로 두는
-- scripts/db/runtime-role.sql 과 함께 쓴다.

CREATE FUNCTION reject_settlement_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'settlement_ledger_is_append-only';
END;
$$;

CREATE TRIGGER settlement_journal_append_only BEFORE DELETE OR UPDATE ON settlement_journal
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_ledger_mutation();
CREATE TRIGGER settlement_journal_no_truncate BEFORE TRUNCATE ON settlement_journal
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_ledger_mutation();
CREATE TRIGGER settlement_entry_append_only BEFORE DELETE OR UPDATE ON settlement_entry
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_ledger_mutation();
CREATE TRIGGER settlement_entry_no_truncate BEFORE TRUNCATE ON settlement_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_ledger_mutation();
CREATE TRIGGER settlement_seller_allocation_append_only BEFORE DELETE OR UPDATE ON settlement_seller_allocation
    FOR EACH ROW EXECUTE FUNCTION reject_settlement_ledger_mutation();
CREATE TRIGGER settlement_seller_allocation_no_truncate BEFORE TRUNCATE ON settlement_seller_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_ledger_mutation();

CREATE FUNCTION assert_settlement_journal_balanced() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    entry_count bigint;
    debit_total numeric;
    credit_total numeric;
BEGIN
    SELECT count(*),
           COALESCE(sum(amount_won) FILTER (WHERE entry_side = 'DEBIT'), 0),
           COALESCE(sum(amount_won) FILTER (WHERE entry_side = 'CREDIT'), 0)
      INTO entry_count, debit_total, credit_total
      FROM settlement_entry
     WHERE journal_id = NEW.journal_id;
    IF entry_count = 0 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'settlement_journal_unbalanced journal=% entries=% debit=% credit=%',
            NEW.journal_id, entry_count, debit_total, credit_total
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER settlement_journal_entries_balanced AFTER INSERT ON settlement_journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_settlement_journal_balanced();
CREATE CONSTRAINT TRIGGER settlement_entry_journal_balanced AFTER INSERT ON settlement_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_settlement_journal_balanced();
