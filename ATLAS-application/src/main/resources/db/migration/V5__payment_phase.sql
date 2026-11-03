ALTER TABLE payment_attempt RENAME COLUMN operation TO payment_phase;
ALTER TABLE payment_attempt RENAME CONSTRAINT payment_attempt_operation TO payment_attempt_payment_phase;
