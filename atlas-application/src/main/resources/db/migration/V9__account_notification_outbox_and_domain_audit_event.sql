-- V9: (1) 인증 메일 outbox, (2) 통합 감사·도메인 이벤트 로그 (#718, ADR-0102, EXP-113).
--
-- 이 파일은 V8(#717, 큐 폴링 부분 색인)의 내용에 기대지 않는다. 새 테이블 둘과 새 트리거만 만들고 V8이 다루는
-- 객체를 바꾸지 않으므로 V8 앞뒤 어느 순서로 적용돼도 결과가 같다. 번호는 V8 뒤에 병합되도록 9를 쓴다.
--
-- (1) 인증 메일 outbox
--
-- 토큰은 이 테이블에 어떤 형태로도 두지 않는다(평문·해시·암호문 모두 없음). worker가 전송 직전에 토큰을 발급하고
-- 해시만 account_action_token에 쓴다. 수신 주소도 두지 않고 전송 시점에 user_account에서 읽는다.
-- (계정, 목적)마다 대기 행은 하나다. 같은 목적의 재요청은 기존 행을 다시 대기로 돌린다.
-- 성공한 행은 지운다. FAILED 행은 운영자가 볼 수 있도록 남기고, 사용자가 다시 요청하면 PENDING으로 돌아간다.
CREATE TABLE account_notification_outbox (
    account_id uuid NOT NULL REFERENCES user_account(account_id),
    purpose character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'PENDING' NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    -- 다음 시도 시각. 청구 중에는 임대 만료 시각으로 쓰여 죽은 worker의 행이 그 뒤에 다시 보인다.
    available_at timestamp with time zone NOT NULL,
    lease_owner uuid,
    -- 예외 클래스 이름만 둔다. 메시지에는 수신 주소가 섞일 수 있다.
    last_failure character varying(128),
    CONSTRAINT account_notification_outbox_pkey PRIMARY KEY (account_id, purpose),
    CONSTRAINT ck_account_notification_outbox_purpose
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_notification_outbox_status CHECK (status IN ('PENDING', 'FAILED')),
    CONSTRAINT ck_account_notification_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_account_notification_outbox_due
    ON account_notification_outbox (available_at)
    WHERE status = 'PENDING';

-- (2) 통합 감사·도메인 이벤트 로그
--
-- 감사 행이 테이블 9개에 흩어져 "운영자 X의 조치 전부"나 "주문·지급 하나의 돈 이벤트" 같은 질문을 한 질의로 답할 수
-- 없었다. 원본 테이블의 AFTER INSERT 트리거가 같은 transaction에서 이 로그에 한 행을 쓴다. 쓰기 코드를 고치지 않으므로
-- 앞으로 생기는 쓰기 경로도 빠지지 않고, 원본 쓰기가 롤백되면 로그 행도 없다.
-- payload는 원본 행의 jsonb에서 자유 문자열(reason)과 멱등 키·요청 해시를 뺀 것이다. 자세한 사유는 source_table과
-- source_id로 원본에서 읽는다. 로그는 추가 전용이다(UPDATE·DELETE·TRUNCATE 거절).
-- tx_id는 ADR-0067의 이벤트 로그와 같은 xmin 안전 cursor(커밋된 transaction까지만 읽기)를 위한 값이다.
CREATE TABLE domain_audit_event (
    event_id uuid DEFAULT uuidv7() NOT NULL,
    tx_id xid8 DEFAULT pg_current_xact_id() NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    source_table character varying(64) NOT NULL,
    source_id character varying(256) NOT NULL,
    event_type character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id character varying(256) NOT NULL,
    actor character varying(128),
    payload jsonb NOT NULL,
    CONSTRAINT domain_audit_event_pkey PRIMARY KEY (event_id)
);

CREATE INDEX ix_domain_audit_event_actor ON domain_audit_event (actor, occurred_at DESC) WHERE actor IS NOT NULL;
CREATE INDEX ix_domain_audit_event_aggregate ON domain_audit_event (aggregate_type, aggregate_id, occurred_at);
CREATE INDEX ix_domain_audit_event_tx ON domain_audit_event (tx_id);

CREATE FUNCTION record_domain_audit_event() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    source jsonb := to_jsonb(NEW);
    aggregate_type text;
    aggregate_id text;
    event_type text;
    source_id text;
    occurred timestamp with time zone;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'account_suspension_action' THEN
            aggregate_type := 'account'; aggregate_id := source->>'account_id';
            source_id := source->>'suspension_action_id'; event_type := 'ACCOUNT_' || (source->>'action');
        WHEN 'payment_recovery_operator_action' THEN
            aggregate_type := 'payment_recovery_job'; aggregate_id := source->>'recovery_job_id';
            source_id := source->>'operator_action_id'; event_type := 'PAYMENT_RECOVERY_REQUEUED';
        WHEN 'paypal_webhook_retry_operator_audit' THEN
            aggregate_type := 'paypal_webhook_event'; aggregate_id := source->>'event_id';
            source_id := (source->>'event_id') || ':' || (source->>'idempotency_key');
            event_type := 'PAYPAL_WEBHOOK_' || (source->>'outcome');
        WHEN 'seller_payout_profile_action' THEN
            aggregate_type := 'seller'; aggregate_id := source->>'seller_id';
            source_id := source->>'action_id'; event_type := 'PAYOUT_PROFILE_' || (source->>'action');
        WHEN 'settlement_payout_operator_action' THEN
            aggregate_type := 'payout'; aggregate_id := source->>'payout_id';
            source_id := source->>'action_id'; event_type := 'PAYOUT_' || (source->>'action');
        WHEN 'paypal_balance_report_disposition' THEN
            aggregate_type := 'paypal_balance_report_row';
            aggregate_id := (source->>'run_id') || ':' || (source->>'row_number');
            source_id := source->>'disposition_id'; event_type := 'BALANCE_REPORT_' || (source->>'disposition');
        WHEN 'inventory_capacity_adjustment' THEN
            aggregate_type := 'sku'; aggregate_id := (source->>'seller_id') || ':' || (source->>'sku_id');
            source_id := (source->>'seller_id') || ':' || (source->>'idempotency_key');
            event_type := 'INVENTORY_CAPACITY_ADJUSTED';
        WHEN 'inventory_transfer' THEN
            aggregate_type := 'sku'; aggregate_id := (source->>'seller_id') || ':' || (source->>'sku_id');
            source_id := source->>'transfer_id'; event_type := 'INVENTORY_TRANSFERRED';
        WHEN 'settlement_journal' THEN
            source_id := source->>'journal_id';
            IF source->>'source_payout_id' IS NOT NULL THEN
                aggregate_type := 'payout'; aggregate_id := source->>'source_payout_id';
                event_type := 'PAYOUT_JOURNAL_POSTED';
            ELSIF source->>'source_refund_id' IS NOT NULL THEN
                aggregate_type := 'refund'; aggregate_id := source->>'source_refund_id';
                event_type := 'REFUND_JOURNAL_POSTED';
            ELSIF source->>'checkout_id' IS NOT NULL THEN
                aggregate_type := 'checkout'; aggregate_id := source->>'checkout_id';
                event_type := 'CHECKOUT_CAPTURE_JOURNAL_POSTED';
            ELSE
                aggregate_type := 'order'; aggregate_id := source->>'source_order_id';
                event_type := 'CAPTURE_JOURNAL_POSTED';
            END IF;
        ELSE
            RAISE EXCEPTION 'domain audit event source % is not mapped', TG_TABLE_NAME;
    END CASE;
    occurred := coalesce((source->>'created_at')::timestamptz, (source->>'recorded_at')::timestamptz, now());
    INSERT INTO domain_audit_event(occurred_at, source_table, source_id, event_type, aggregate_type, aggregate_id,
                                   actor, payload)
    VALUES (occurred, TG_TABLE_NAME, coalesce(source_id, ''), event_type, coalesce(aggregate_type, 'unknown'),
            coalesce(aggregate_id, ''), source->>'actor',
            source - 'reason' - 'idempotency_key' - 'request_facts_sha256');
    RETURN NULL;
END;
$$;

CREATE FUNCTION reject_domain_audit_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'domain_audit_event_is_append-only';
END;
$$;

CREATE TRIGGER domain_audit_event_append_only BEFORE DELETE OR UPDATE ON domain_audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_domain_audit_event_mutation();
CREATE TRIGGER domain_audit_event_no_truncate BEFORE TRUNCATE ON domain_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_domain_audit_event_mutation();

CREATE TRIGGER account_suspension_action_audit AFTER INSERT ON account_suspension_action
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER payment_recovery_operator_action_audit AFTER INSERT ON payment_recovery_operator_action
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER paypal_webhook_retry_operator_audit_audit AFTER INSERT ON paypal_webhook_retry_operator_audit
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER seller_payout_profile_action_audit AFTER INSERT ON seller_payout_profile_action
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER settlement_payout_operator_action_audit AFTER INSERT ON settlement_payout_operator_action
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER paypal_balance_report_disposition_audit AFTER INSERT ON paypal_balance_report_disposition
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER inventory_capacity_adjustment_audit AFTER INSERT ON inventory_capacity_adjustment
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER inventory_transfer_audit AFTER INSERT ON inventory_transfer
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
CREATE TRIGGER settlement_journal_audit AFTER INSERT ON settlement_journal
    FOR EACH ROW EXECUTE FUNCTION record_domain_audit_event();
