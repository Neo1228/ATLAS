-- EXP-032: idle expiry candidate probe.
-- Run only in a disposable PostgreSQL 18.6 database; never production.

CREATE TABLE purchase_order (
    order_id UUID PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    checkout_id UUID,
    checkout_expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX purchase_order_expiry_idx ON purchase_order (checkout_expires_at)
    WHERE state = 'RESERVED';

CREATE TABLE admission_ticket (
    ticket_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX admission_ticket_expiry_idx ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');

-- 200k non-reserved + 2k future-reserved orders; no due expiry backlog.
INSERT INTO purchase_order
SELECT gen_random_uuid(), 'PAID', NULL, CURRENT_TIMESTAMP - INTERVAL '1 day'
FROM generate_series(1, 200000);
INSERT INTO purchase_order
SELECT gen_random_uuid(), 'RESERVED', NULL, CURRENT_TIMESTAMP + INTERVAL '10 minutes'
FROM generate_series(1, 2000);

-- 20k future admission tickets; no due expiry backlog.
INSERT INTO admission_ticket
SELECT gen_random_uuid(), gen_random_uuid(), 'sku-' || g, 'WAITING', CURRENT_TIMESTAMP + INTERVAL '5 minutes'
FROM generate_series(1, 20000) AS g;

VACUUM (ANALYZE) purchase_order;
VACUUM (ANALYZE) admission_ticket;

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT order_id
FROM purchase_order
WHERE checkout_id IS NULL
  AND state = 'RESERVED'
  AND checkout_expires_at <= CURRENT_TIMESTAMP
ORDER BY checkout_expires_at, order_id
LIMIT 100;

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT ticket_id, seller_id, sku_id, expires_at
FROM admission_ticket
WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE')
  AND expires_at <= CURRENT_TIMESTAMP
ORDER BY expires_at, ticket_id
LIMIT 200;
