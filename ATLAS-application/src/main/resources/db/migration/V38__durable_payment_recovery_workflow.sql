CREATE TABLE payment_recovery_job (
    recovery_job_id UUID PRIMARY KEY,
    payment_attempt_id UUID REFERENCES payment_attempt (attempt_id),
    payment_refund_operation_id UUID REFERENCES payment_refund_operation (refund_operation_id),
    operation_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    first_enqueued_at TIMESTAMPTZ NOT NULL,
    retry_window_started_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    manual_requeue_count INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_recovery_job_operation_type_check
        CHECK (operation_type IN ('PAYMENT_ATTEMPT', 'PAYMENT_REFUND')),
    CONSTRAINT payment_recovery_job_operation_reference_check
        CHECK (
            (operation_type = 'PAYMENT_ATTEMPT'
                AND payment_attempt_id IS NOT NULL
                AND payment_refund_operation_id IS NULL)
            OR (operation_type = 'PAYMENT_REFUND'
                AND payment_attempt_id IS NULL
                AND payment_refund_operation_id IS NOT NULL)
        ),
    CONSTRAINT payment_recovery_job_status_check
        CHECK (status IN ('PENDING', 'WAITING_FOR_BUYER', 'COMPLETED', 'NEEDS_REVIEW')),
    CONSTRAINT payment_recovery_job_attempt_count_check
        CHECK (attempt_count >= 0 AND manual_requeue_count >= 0),
    CONSTRAINT payment_recovery_job_lease_check
        CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT payment_recovery_job_attempt_unique UNIQUE (payment_attempt_id),
    CONSTRAINT payment_recovery_job_refund_unique UNIQUE (payment_refund_operation_id)
);

CREATE INDEX ix_payment_recovery_job_due
    ON payment_recovery_job (available_at, first_enqueued_at, recovery_job_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_payment_recovery_job_needs_review
    ON payment_recovery_job (first_enqueued_at DESC, recovery_job_id DESC)
    WHERE status = 'NEEDS_REVIEW';

INSERT INTO payment_recovery_job (
    recovery_job_id, payment_attempt_id, operation_type, status, available_at,
    first_enqueued_at, retry_window_started_at, updated_at
)
SELECT
    gen_random_uuid(),
    attempt.attempt_id,
    'PAYMENT_ATTEMPT',
    CASE
        WHEN attempt.state = 'AUTHORIZATION_PENDING'
            AND attempt.provider_order_id IS NOT NULL
            AND attempt.approval_url IS NOT NULL
            AND length(trim(attempt.approval_url)) > 0
            AND (
                payment_order.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN')
                OR payment_checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN')
            )
        THEN 'WAITING_FOR_BUYER'
        ELSE 'PENDING'
    END,
    CURRENT_TIMESTAMP + INTERVAL '30 seconds',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM payment_attempt AS attempt
LEFT JOIN purchase_order AS payment_order ON payment_order.order_id = attempt.order_id
LEFT JOIN buyer_checkout AS payment_checkout ON payment_checkout.checkout_id = attempt.checkout_id
WHERE attempt.state IN (
    'NOT_STARTED', 'AUTHORIZATION_PENDING', 'AUTHORIZED',
    'CAPTURE_PENDING', 'UNKNOWN', 'VOID_PENDING'
);

INSERT INTO payment_recovery_job (
    recovery_job_id, payment_refund_operation_id, operation_type, status,
    available_at, first_enqueued_at, retry_window_started_at, updated_at
)
SELECT
    gen_random_uuid(),
    operation.refund_operation_id,
    'PAYMENT_REFUND',
    'PENDING',
    CURRENT_TIMESTAMP + INTERVAL '30 seconds',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM payment_refund_operation AS operation
WHERE operation.refund_state IN ('PENDING', 'UNKNOWN');

CREATE TABLE payment_recovery_operator_action (
    operator_action_id UUID PRIMARY KEY,
    recovery_job_id UUID NOT NULL REFERENCES payment_recovery_job (recovery_job_id),
    idempotency_key VARCHAR(128) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_recovery_operator_action_key_unique
        UNIQUE (recovery_job_id, idempotency_key),
    CONSTRAINT payment_recovery_operator_action_reason_check
        CHECK (length(trim(reason)) BETWEEN 1 AND 500)
);

CREATE INDEX ix_payment_recovery_operator_action_created
    ON payment_recovery_operator_action (recovery_job_id, created_at DESC);
