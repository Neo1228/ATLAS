-- Issue #277: 검증된 이벤트의 일시적 상관관계 실패를 재처리하는 데 필요한 필드만 저장한다.
-- 원본 body, header, signature, provider credential은 저장하지 않는다.
CREATE TABLE paypal_webhook_retry (
    event_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    resource_id VARCHAR(128),
    resource_status VARCHAR(64),
    provider_order_id VARCHAR(128),
    related_capture_id VARCHAR(128),
    resource_amount NUMERIC(19, 4),
    resource_currency VARCHAR(3),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'EXHAUSTED')),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(36),
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(1000),
    finished_at TIMESTAMPTZ,
    CONSTRAINT paypal_webhook_retry_exhausted_terminal
        CHECK (state <> 'EXHAUSTED' OR finished_at IS NOT NULL)
);

CREATE INDEX paypal_webhook_retry_due_idx
    ON paypal_webhook_retry (next_attempt_at, created_at)
    WHERE state = 'PENDING' AND attempt_count < 8;

CREATE INDEX paypal_webhook_retry_exhausted_idx
    ON paypal_webhook_retry (finished_at)
    WHERE state = 'EXHAUSTED';
