-- Supports the bounded, ordered sweep of pending retries at the attempt limit.
CREATE INDEX paypal_webhook_retry_reaper_idx
    ON paypal_webhook_retry (lease_until, event_id)
    WHERE state = 'PENDING' AND attempt_count = 8;
