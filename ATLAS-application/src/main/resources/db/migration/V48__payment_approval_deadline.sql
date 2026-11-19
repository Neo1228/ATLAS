ALTER TABLE payment_attempt
    ADD COLUMN approval_expires_at TIMESTAMPTZ;

-- A buyer who abandons the PayPal approval page never sends a callback, so
-- WAITING_FOR_BUYER jobs had no deadline and no alert path. Backfill a deadline only for
-- attempts that are still parked in the approval wait, using first_enqueued_at as the
-- baseline so already-stale rows become due immediately instead of being granted a fresh
-- full window at deploy time.
UPDATE payment_attempt AS attempt
SET approval_expires_at = work.first_enqueued_at + INTERVAL '30 minutes'
FROM payment_recovery_job AS work
WHERE work.payment_attempt_id = attempt.attempt_id
  AND attempt.approval_expires_at IS NULL
  AND attempt.state = 'AUTHORIZATION_PENDING'
  AND attempt.payment_phase = 'AUTHORIZATION'
  AND attempt.authorization_active
  AND attempt.provider_order_id IS NOT NULL
  AND attempt.approval_url IS NOT NULL;

-- Sweep candidate: parked approval waits whose deadline has passed. Partial index keeps
-- the scan proportional to overdue rows rather than to all attempts.
CREATE INDEX ix_payment_attempt_approval_expiry
    ON payment_attempt (approval_expires_at, attempt_id)
    WHERE state = 'AUTHORIZATION_PENDING' AND approval_expires_at IS NOT NULL;
