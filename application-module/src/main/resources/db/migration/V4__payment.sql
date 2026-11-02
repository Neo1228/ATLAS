CREATE TABLE payment_attempt (
    order_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    authorization_active BOOLEAN NOT NULL,
    CONSTRAINT payment_attempt_pk PRIMARY KEY (order_id),
    CONSTRAINT payment_attempt_attempt_id_unique UNIQUE (attempt_id),
    CONSTRAINT payment_attempt_state CHECK (state IN ('NOT_STARTED', 'AUTHORIZATION_PENDING', 'AUTHORIZED', 'CAPTURE_PENDING', 'CAPTURED', 'DECLINED', 'UNKNOWN', 'VOID_PENDING', 'VOIDED')),
    CONSTRAINT payment_attempt_operation CHECK (operation IN ('AUTHORIZATION', 'CAPTURE', 'VOID'))
);
