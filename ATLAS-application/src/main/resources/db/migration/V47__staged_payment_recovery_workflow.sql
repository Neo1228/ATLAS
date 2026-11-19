ALTER TABLE payment_recovery_job
    ADD COLUMN work_stage VARCHAR(16) NOT NULL DEFAULT 'PROVIDER',
    ADD CONSTRAINT payment_recovery_job_work_stage_check
        CHECK (work_stage IN ('PROVIDER', 'APPLY'));

-- A pending single-order intent without an attempt cannot have reached PayPal: the code
-- persists the attempt before making the first provider request. Reconstruct only from
-- immutable order-item snapshots. Legacy campaign orders created before the item snapshot
-- existed have no rows here and are left untouched (they were already unresolved without
-- an attempt, and inventing an amount would create a false payment intent). If a snapshot
-- exists but its total is not a valid positive BIGINT, refuse rather than guess.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM purchase_order AS po
        JOIN LATERAL (
            SELECT COUNT(*) AS item_count,
                   SUM(oi.unit_price_won::numeric * oi.quantity - oi.coupon_discount_won) AS total_won
            FROM order_item AS oi
            WHERE oi.order_id = po.order_id
        ) AS snapshot ON snapshot.item_count > 0
        WHERE po.checkout_id IS NULL
          AND po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                           'PAYMENT_VOID_PENDING', 'CANCEL_PENDING')
          AND NOT EXISTS (SELECT 1 FROM payment_attempt pa WHERE pa.order_id = po.order_id)
          AND (snapshot.total_won <= 0
               OR snapshot.total_won > 9223372036854775807)
    ) THEN
        RAISE EXCEPTION 'Cannot backfill pending order payment attempt: immutable item total is invalid';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM buyer_checkout AS checkout
        WHERE checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING')
          AND checkout.total_won <= 0
          AND NOT EXISTS (SELECT 1 FROM payment_attempt pa WHERE pa.checkout_id = checkout.checkout_id)
    ) THEN
        RAISE EXCEPTION 'Cannot backfill pending checkout payment attempt: checkout total is not positive';
    END IF;
END
$$;

WITH missing_order_attempt AS (
    SELECT po.order_id,
           SUM(oi.unit_price_won::numeric * oi.quantity - oi.coupon_discount_won)::bigint AS total_won
    FROM purchase_order AS po
    JOIN order_item AS oi ON oi.order_id = po.order_id
    WHERE po.checkout_id IS NULL
      AND po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                       'PAYMENT_VOID_PENDING', 'CANCEL_PENDING')
      AND NOT EXISTS (SELECT 1 FROM payment_attempt pa WHERE pa.order_id = po.order_id)
    GROUP BY po.order_id
)
INSERT INTO payment_attempt (
    order_id, attempt_id, state, payment_phase, authorization_active, total_won
)
SELECT order_id, uuidv7(), 'NOT_STARTED', 'AUTHORIZATION', FALSE, total_won
FROM missing_order_attempt
ON CONFLICT (order_id) DO NOTHING;

INSERT INTO payment_attempt (
    checkout_id, attempt_id, state, payment_phase, authorization_active, total_won
)
SELECT checkout.checkout_id, uuidv7(), 'NOT_STARTED', 'AUTHORIZATION', FALSE, checkout.total_won
FROM buyer_checkout AS checkout
WHERE checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING')
  AND NOT EXISTS (
      SELECT 1 FROM payment_attempt pa WHERE pa.checkout_id = checkout.checkout_id
  )
ON CONFLICT (checkout_id) DO NOTHING;

UPDATE purchase_order AS po
SET payment_attempt_id = attempt.attempt_id
FROM payment_attempt AS attempt
WHERE po.order_id = attempt.order_id
  AND po.payment_attempt_id IS NULL
  AND po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                   'PAYMENT_VOID_PENDING', 'CANCEL_PENDING');

UPDATE purchase_order AS po
SET payment_attempt_id = attempt.attempt_id
FROM payment_attempt AS attempt
WHERE po.checkout_id = attempt.checkout_id
  AND po.payment_attempt_id IS NULL
  AND po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                   'PAYMENT_VOID_PENDING', 'CANCEL_PENDING');

-- An order refund may have committed before its payment operation was linked. First
-- reconnect an operation already created under the same attempt/idempotency key.
UPDATE order_refund_request AS request
SET payment_refund_operation_id = operation.refund_operation_id
FROM purchase_order AS po
JOIN payment_attempt AS attempt
  ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
  OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
JOIN payment_refund_operation AS operation
  ON operation.attempt_id = attempt.attempt_id
WHERE request.order_id = po.order_id
  AND operation.idempotency_key = request.idempotency_key
  AND request.payment_refund_operation_id IS NULL
  AND request.payment_refund_status IN ('PENDING', 'UNKNOWN');

-- If no operation was persisted, the provider call could not have been issued. Recreate
-- the operation with the original request key and a deterministic amount snapshot from
-- the captured attempt; never reprice against today's exchange rate.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_refund_request AS request
        JOIN purchase_order AS po ON po.order_id = request.order_id
        LEFT JOIN payment_attempt AS attempt
          ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
          OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
        WHERE request.payment_refund_status IN ('PENDING', 'UNKNOWN')
          AND request.payment_refund_operation_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM payment_refund_operation operation
              WHERE operation.attempt_id = attempt.attempt_id
                AND operation.idempotency_key = request.idempotency_key
          )
          AND (attempt.attempt_id IS NULL OR attempt.state <> 'CAPTURED'
               OR attempt.provider_capture_id IS NULL
               OR attempt.provider_currency IS NULL
               OR attempt.provider_currency NOT IN ('USD', 'KRW')
               OR (attempt.provider_currency = 'USD'
                   AND (attempt.provider_amount_minor IS NULL OR attempt.provider_amount_minor <= 0
                        OR attempt.fx_rate_won_per_usd IS NULL OR attempt.fx_rate_won_per_usd <= 0)))
    ) THEN
        RAISE EXCEPTION 'Cannot backfill pending refund: captured attempt/provider snapshot is missing; review before deployment';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM order_refund_request AS request
        JOIN purchase_order AS po ON po.order_id = request.order_id
        JOIN payment_attempt AS attempt
          ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
          OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
        WHERE request.payment_refund_status IN ('PENDING', 'UNKNOWN')
          AND request.payment_refund_operation_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM payment_refund_operation operation
              WHERE operation.attempt_id = attempt.attempt_id
                AND operation.idempotency_key = request.idempotency_key
          )
          AND EXISTS (
              SELECT 1 FROM payment_refund_operation prior
              WHERE prior.attempt_id = attempt.attempt_id
                AND prior.refund_state IN ('PENDING', 'UNKNOWN')
                AND prior.idempotency_key <> request.idempotency_key
          )
    ) THEN
        RAISE EXCEPTION 'Pending refund has another unresolved operation for its capture; review before deployment';
    END IF;

    IF EXISTS (
        SELECT attempt.attempt_id
        FROM order_refund_request AS request
        JOIN purchase_order AS po ON po.order_id = request.order_id
        JOIN payment_attempt AS attempt
          ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
          OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
        WHERE request.payment_refund_status IN ('PENDING', 'UNKNOWN')
          AND request.payment_refund_operation_id IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM payment_refund_operation operation
              WHERE operation.attempt_id = attempt.attempt_id
                AND operation.idempotency_key = request.idempotency_key
          )
        GROUP BY attempt.attempt_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Multiple unlinked pending refunds target one capture; review before deployment';
    END IF;
END
$$;

WITH missing_refund_operation AS (
    SELECT request.refund_id,
           request.idempotency_key,
           request.amount_won,
           attempt.attempt_id,
           attempt.provider_capture_id,
           attempt.provider_currency,
           CASE
               WHEN attempt.provider_currency = 'USD' THEN LEAST(
                   ROUND(request.amount_won::numeric / attempt.fx_rate_won_per_usd * 100)::bigint,
                   attempt.provider_amount_minor - COALESCE((
                       SELECT SUM(prior.provider_amount_minor)
                       FROM payment_refund_operation AS prior
                       WHERE prior.attempt_id = attempt.attempt_id
                         AND prior.refund_state <> 'FAILED'
                   ), 0)
               )
               WHEN attempt.provider_currency = 'KRW' THEN request.amount_won
           END AS provider_amount_minor
    FROM order_refund_request AS request
    JOIN purchase_order AS po ON po.order_id = request.order_id
    JOIN payment_attempt AS attempt
      ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
      OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
    WHERE request.payment_refund_status IN ('PENDING', 'UNKNOWN')
      AND request.payment_refund_operation_id IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM payment_refund_operation AS operation
          WHERE operation.attempt_id = attempt.attempt_id
            AND operation.idempotency_key = request.idempotency_key
      )
)
INSERT INTO payment_refund_operation (
    refund_operation_id, attempt_id, capture_id, idempotency_key, amount_won,
    currency, provider_amount_minor, provider_currency, provider_request_id,
    refund_state, created_at, updated_at
)
SELECT uuidv7(), attempt_id, provider_capture_id, idempotency_key, amount_won,
       'KRW', provider_amount_minor, provider_currency, uuidv7(),
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM missing_refund_operation;

UPDATE order_refund_request AS request
SET payment_refund_operation_id = operation.refund_operation_id
FROM purchase_order AS po
JOIN payment_attempt AS attempt
  ON (po.checkout_id IS NULL AND attempt.order_id = po.order_id)
  OR (po.checkout_id IS NOT NULL AND attempt.checkout_id = po.checkout_id)
JOIN payment_refund_operation AS operation
  ON operation.attempt_id = attempt.attempt_id
WHERE request.order_id = po.order_id
  AND operation.idempotency_key = request.idempotency_key
  AND request.payment_refund_operation_id IS NULL
  AND request.payment_refund_status IN ('PENDING', 'UNKNOWN');

-- An owner/application stage is distinct from provider terminal state. Re-open terminal
-- operations only when the owner has not yet recorded that result.
UPDATE payment_recovery_job AS job
SET work_stage = 'APPLY',
    status = 'PENDING',
    available_at = CURRENT_TIMESTAMP,
    retry_window_started_at = CURRENT_TIMESTAMP,
    attempt_count = 0,
    lease_token = NULL,
    lease_until = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM payment_attempt AS attempt
LEFT JOIN purchase_order AS po ON po.order_id = attempt.order_id
LEFT JOIN buyer_checkout AS checkout ON checkout.checkout_id = attempt.checkout_id
WHERE job.payment_attempt_id = attempt.attempt_id
  AND attempt.state IN ('CAPTURED', 'DECLINED', 'VOIDED')
  AND ((po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                     'PAYMENT_VOID_PENDING', 'CANCEL_PENDING'))
       OR (checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING')));

UPDATE payment_recovery_job AS job
SET work_stage = 'APPLY',
    status = 'PENDING',
    available_at = CURRENT_TIMESTAMP,
    retry_window_started_at = CURRENT_TIMESTAMP,
    attempt_count = 0,
    lease_token = NULL,
    lease_until = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM payment_refund_operation AS operation
JOIN order_refund_request AS request
  ON request.payment_refund_operation_id = operation.refund_operation_id
WHERE job.payment_refund_operation_id = operation.refund_operation_id
  AND operation.refund_state IN ('COMPLETED', 'FAILED')
  AND request.payment_refund_status IN ('PENDING', 'UNKNOWN');

-- Restore jobs missing from legacy attempts/operations. Owners with no recoverable
-- reference are retained for operator review, not silently discarded.
INSERT INTO payment_recovery_job (
    recovery_job_id, payment_attempt_id, operation_type, work_stage, status,
    available_at, first_enqueued_at, retry_window_started_at, updated_at, last_error_code
)
SELECT uuidv7(), attempt.attempt_id, 'PAYMENT_ATTEMPT',
       CASE WHEN attempt.state IN ('CAPTURED', 'DECLINED', 'VOIDED')
                  AND (po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                                    'PAYMENT_VOID_PENDING', 'CANCEL_PENDING')
                       OR checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING'))
            THEN 'APPLY' ELSE 'PROVIDER' END,
       CASE WHEN po.order_id IS NULL AND checkout.checkout_id IS NULL
            THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
       CASE WHEN po.order_id IS NULL AND checkout.checkout_id IS NULL
            THEN 'PAYMENT_OWNER_MISSING' ELSE NULL END
FROM payment_attempt AS attempt
LEFT JOIN purchase_order AS po ON po.order_id = attempt.order_id
LEFT JOIN buyer_checkout AS checkout ON checkout.checkout_id = attempt.checkout_id
LEFT JOIN payment_recovery_job AS job ON job.payment_attempt_id = attempt.attempt_id
WHERE job.recovery_job_id IS NULL
  AND (attempt.state NOT IN ('CAPTURED', 'DECLINED', 'VOIDED')
       OR po.state IN ('PAYMENT_SUBMITTED', 'PAYMENT_PENDING', 'PAYMENT_UNKNOWN',
                       'PAYMENT_VOID_PENDING', 'CANCEL_PENDING')
       OR checkout.state IN ('PAYMENT_PENDING', 'PAYMENT_UNKNOWN', 'CANCEL_PENDING')
       OR (po.order_id IS NULL AND checkout.checkout_id IS NULL))
ON CONFLICT (payment_attempt_id) DO NOTHING;

INSERT INTO payment_recovery_job (
    recovery_job_id, payment_refund_operation_id, operation_type, work_stage, status,
    available_at, first_enqueued_at, retry_window_started_at, updated_at, last_error_code
)
SELECT uuidv7(), operation.refund_operation_id, 'PAYMENT_REFUND',
       CASE WHEN operation.refund_state IN ('COMPLETED', 'FAILED')
                  AND request.payment_refund_status IN ('PENDING', 'UNKNOWN')
            THEN 'APPLY' ELSE 'PROVIDER' END,
       CASE WHEN request.refund_id IS NULL THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
       CASE WHEN request.refund_id IS NULL THEN 'REFUND_OWNER_MISSING' ELSE NULL END
FROM payment_refund_operation AS operation
LEFT JOIN order_refund_request AS request
  ON request.payment_refund_operation_id = operation.refund_operation_id
LEFT JOIN payment_recovery_job AS job
  ON job.payment_refund_operation_id = operation.refund_operation_id
WHERE job.recovery_job_id IS NULL
  AND (operation.refund_state NOT IN ('COMPLETED', 'FAILED')
       OR request.payment_refund_status IN ('PENDING', 'UNKNOWN')
       OR request.refund_id IS NULL)
ON CONFLICT (payment_refund_operation_id) DO NOTHING;
