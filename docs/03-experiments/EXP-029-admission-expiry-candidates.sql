-- EXP-029: admission expiry keyset candidate plan comparison.
-- Run only in a disposable PostgreSQL 18.6 database; never run in production.
-- V41 migration replacement is repeated below so the experiment is standalone.

CREATE TABLE admission_ticket (
    ticket_id UUID PRIMARY KEY,
    seller_id UUID NOT NULL,
    sku_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX admission_ticket_expiry_idx
    ON admission_ticket (expires_at)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');

-- 500,000 due + 500,000 future ticket rows.
INSERT INTO admission_ticket
SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'sku-' || (g % 10000),
       'WAITING', CURRENT_TIMESTAMP - INTERVAL '10 minutes'
FROM generate_series(1, 500000) AS g;
INSERT INTO admission_ticket
SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'sku-' || (g % 10000),
       'WAITING', CURRENT_TIMESTAMP + INTERVAL '10 minutes'
FROM generate_series(1, 500000) AS g;

-- V41__admission_expiry_keyset_index.sql replaces the old timestamp-only index.
DROP INDEX admission_ticket_expiry_idx;
CREATE INDEX admission_ticket_expiry_idx
    ON admission_ticket (expires_at, ticket_id)
    WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE');
VACUUM (ANALYZE) admission_ticket;

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT ticket_id, seller_id, sku_id, expires_at
FROM admission_ticket
WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
ORDER BY expires_at, ticket_id
LIMIT 200;

SELECT expires_at AS cursor_at, ticket_id AS cursor_ticket_id
FROM admission_ticket
WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
ORDER BY expires_at, ticket_id OFFSET 199 LIMIT 1
\gset

EXPLAIN (ANALYZE, BUFFERS, SUMMARY)
SELECT ticket_id, seller_id, sku_id, expires_at
FROM admission_ticket
WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
  AND (expires_at, ticket_id) > (:'cursor_at'::timestamptz, :'cursor_ticket_id'::uuid)
ORDER BY expires_at, ticket_id
LIMIT 200;
