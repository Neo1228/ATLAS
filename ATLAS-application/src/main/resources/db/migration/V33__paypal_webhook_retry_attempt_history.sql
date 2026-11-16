-- Queue 행 삭제·재생성 및 재시도 횟수 초기화와 무관하게 이력을 보존한다.
-- 고유 lease token이 claim을 식별하며 변경 가능한 queue에 대한 FK는 두지 않는다.
CREATE TABLE paypal_webhook_retry_attempt_history (
    lease_token VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    outcome VARCHAR(32) NOT NULL DEFAULT 'CLAIMED' CHECK (outcome IN (
        'CLAIMED', 'CORRELATION_MISS', 'TRANSIENT_ERROR', 'PROCESSED',
        'PERMANENT_REJECT', 'EXHAUSTED', 'LEASE_EXPIRED'
    )),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CHECK ((outcome = 'CLAIMED') = (finished_at IS NULL))
);

CREATE INDEX paypal_webhook_retry_attempt_history_claimed_idx
    ON paypal_webhook_retry_attempt_history (event_id, claimed_at DESC);

-- Claim 식별자와 시각은 불변이며 claim은 종결 outcome으로 한 번만 전이할 수 있다.
-- 종결된 행과 이력은 수정·삭제할 수 없다.
CREATE FUNCTION guard_paypal_webhook_retry_attempt_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.outcome = 'CLAIMED' AND NEW.outcome <> 'CLAIMED'
       AND NEW.lease_token = OLD.lease_token AND NEW.event_id = OLD.event_id
       AND NEW.attempt_number = OLD.attempt_number AND NEW.claimed_at = OLD.claimed_at
       AND NEW.finished_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'PayPal webhook retry attempt history is immutable after finalization';
END;
$$;

CREATE TRIGGER paypal_webhook_retry_attempt_history_guard
    BEFORE UPDATE OR DELETE ON paypal_webhook_retry_attempt_history
    FOR EACH ROW EXECUTE FUNCTION guard_paypal_webhook_retry_attempt_history();

CREATE TRIGGER paypal_webhook_retry_attempt_history_no_truncate
    BEFORE TRUNCATE ON paypal_webhook_retry_attempt_history
    FOR EACH STATEMENT EXECUTE FUNCTION guard_paypal_webhook_retry_attempt_history();
