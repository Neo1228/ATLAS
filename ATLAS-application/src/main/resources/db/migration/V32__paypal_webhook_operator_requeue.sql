-- Issue #279: operator가 시작한 재시도는 횟수를 제한하고 감사 기록으로 보존한다.
ALTER TABLE paypal_webhook_retry
    ADD COLUMN manual_requeue_count INTEGER NOT NULL DEFAULT 0
        CHECK (manual_requeue_count BETWEEN 0 AND 3);

CREATE TABLE paypal_webhook_retry_operator_audit (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('REQUEUED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX paypal_webhook_retry_operator_audit_event_idx
    ON paypal_webhook_retry_operator_audit(event_id, created_at);

CREATE FUNCTION reject_paypal_webhook_retry_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'operator retry audit rows are immutable';
END;
$$;

CREATE TRIGGER paypal_webhook_retry_operator_audit_immutable
    BEFORE UPDATE OR DELETE ON paypal_webhook_retry_operator_audit
    FOR EACH ROW EXECUTE FUNCTION reject_paypal_webhook_retry_audit_mutation();
