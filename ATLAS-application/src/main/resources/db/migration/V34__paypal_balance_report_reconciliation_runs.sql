CREATE TABLE paypal_balance_report_run (
    run_id UUID PRIMARY KEY,
    report_sha256 VARCHAR(64) NOT NULL UNIQUE CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    report_format VARCHAR(16) NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count BETWEEN 0 AND 10000),
    matched_count INTEGER NOT NULL CHECK (matched_count >= 0),
    unmatched_count INTEGER NOT NULL CHECK (unmatched_count >= 0),
    mismatch_count INTEGER NOT NULL CHECK (mismatch_count >= 0),
    duplicate_count INTEGER NOT NULL CHECK (duplicate_count >= 0),
    CHECK (matched_count + unmatched_count + mismatch_count + duplicate_count = row_count)
);

CREATE INDEX idx_paypal_balance_report_run_uploaded_at
    ON paypal_balance_report_run (uploaded_at DESC, run_id);

CREATE TABLE paypal_balance_report_row (
    run_id UUID NOT NULL REFERENCES paypal_balance_report_run (run_id),
    row_number INTEGER NOT NULL CHECK (row_number BETWEEN 1 AND 10000),
    record_id VARCHAR(256) NOT NULL,
    related_record_id VARCHAR(256),
    original_record_id VARCHAR(256),
    record_type VARCHAR(64) NOT NULL,
    record_subtype VARCHAR(64),
    currency VARCHAR(3),
    gross_amount NUMERIC(20, 6),
    fee_amount NUMERIC(20, 6),
    net_amount NUMERIC(20, 6),
    transfer_id VARCHAR(256),
    status VARCHAR(16) NOT NULL CHECK (status IN ('MATCHED', 'UNMATCHED', 'DUPLICATE', 'MISMATCH')),
    reason_code VARCHAR(64),
    PRIMARY KEY (run_id, row_number)
);

CREATE INDEX idx_paypal_balance_report_row_run_status
    ON paypal_balance_report_row (run_id, status, row_number);
CREATE INDEX idx_paypal_balance_report_row_record_id
    ON paypal_balance_report_row (record_id, run_id);

CREATE TABLE paypal_balance_report_disposition (
    disposition_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    disposition VARCHAR(32) NOT NULL
        CHECK (disposition IN ('CONFIRMED_EXTERNAL', 'DEFERRED', 'NEEDS_REVIEW')),
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id, row_number)
        REFERENCES paypal_balance_report_row (run_id, row_number)
);

CREATE INDEX idx_paypal_balance_report_disposition_row_history
    ON paypal_balance_report_disposition (run_id, row_number, created_at, disposition_id);
CREATE INDEX idx_paypal_balance_report_disposition_created_at
    ON paypal_balance_report_disposition (created_at DESC, disposition_id);

CREATE FUNCTION reject_paypal_balance_report_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'PayPal balance report history is immutable';
END;
$$;

CREATE TRIGGER paypal_balance_report_run_immutable
BEFORE UPDATE OR DELETE ON paypal_balance_report_run
FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();
CREATE TRIGGER paypal_balance_report_run_no_truncate
BEFORE TRUNCATE ON paypal_balance_report_run
FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();

CREATE TRIGGER paypal_balance_report_row_immutable
BEFORE UPDATE OR DELETE ON paypal_balance_report_row
FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();
CREATE TRIGGER paypal_balance_report_row_no_truncate
BEFORE TRUNCATE ON paypal_balance_report_row
FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();

CREATE TRIGGER paypal_balance_report_disposition_immutable
BEFORE UPDATE OR DELETE ON paypal_balance_report_disposition
FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();
CREATE TRIGGER paypal_balance_report_disposition_no_truncate
BEFORE TRUNCATE ON paypal_balance_report_disposition
FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();
