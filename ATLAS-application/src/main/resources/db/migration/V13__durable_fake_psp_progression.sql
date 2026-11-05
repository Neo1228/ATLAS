CREATE TABLE payment_fake_psp_state (
    payment_phase VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    current_result VARCHAR(32) NOT NULL,
    queued_results TEXT[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (payment_phase, request_id)
);

CREATE TABLE payment_fake_psp_script (
    payment_phase VARCHAR(32) PRIMARY KEY,
    queued_results TEXT[] NOT NULL DEFAULT '{}'
);

CREATE TABLE payment_fake_psp_operation (
    payment_phase VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    result VARCHAR(32) NOT NULL,
    PRIMARY KEY (payment_phase, request_id, idempotency_key),
    FOREIGN KEY (payment_phase, request_id)
        REFERENCES payment_fake_psp_state (payment_phase, request_id)
);
