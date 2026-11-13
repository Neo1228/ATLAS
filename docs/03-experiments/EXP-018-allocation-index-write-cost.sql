-- Run only in a fresh disposable PostgreSQL 18.6 database named atlas_query_evidence.
-- The fixture isolates allocation row and B-tree maintenance; it omits foreign-key checks and parent-table writes.
DO $$
BEGIN
    IF current_database() <> 'atlas_query_evidence' THEN
        RAISE EXCEPTION 'Expected disposable atlas_query_evidence database';
    END IF;
    IF to_regclass('public.exp018_allocation_write_seed') IS NOT NULL
       OR to_regclass('public.exp018_allocation_write_base') IS NOT NULL
       OR to_regclass('public.exp018_allocation_write_candidate') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected fresh allocation write-cost fixture';
    END IF;
END
$$;

CREATE UNLOGGED TABLE exp018_allocation_write_seed AS
SELECT uuidv7() AS journal_id,
       uuidv7() AS order_id,
       ('00000000-0000-0000-0000-' || lpad(to_hex(((n - 1) % 100) + 1), 12, '0'))::uuid AS seller_id,
       2000::bigint AS amount_won,
       0::integer AS allocation_ordinal
FROM generate_series(1, 100000) AS generated(n)
ORDER BY generated.n;


ANALYZE exp018_allocation_write_seed;

CREATE TABLE exp018_allocation_write_base (
    journal_id UUID NOT NULL,
    order_id UUID NOT NULL,
    seller_id UUID NOT NULL,
    amount_won BIGINT NOT NULL,
    allocation_ordinal INTEGER NOT NULL,
    CONSTRAINT exp018_allocation_write_base_pk PRIMARY KEY (journal_id, order_id),
    CONSTRAINT exp018_allocation_write_base_seller_unique UNIQUE (journal_id, seller_id),
    CONSTRAINT exp018_allocation_write_base_ordinal_unique UNIQUE (journal_id, allocation_ordinal),
    CONSTRAINT exp018_allocation_write_base_amount_positive CHECK (amount_won > 0),
    CONSTRAINT exp018_allocation_write_base_ordinal_nonnegative CHECK (allocation_ordinal >= 0)
);

CREATE TABLE exp018_allocation_write_candidate (
    LIKE exp018_allocation_write_base INCLUDING ALL
);

CREATE INDEX exp018_allocation_write_seller_journal_idx
    ON exp018_allocation_write_candidate (seller_id, journal_id);

\echo baseline_run_1
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_base
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_base;

\echo candidate_run_1
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_candidate
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_candidate;

\echo candidate_run_2
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_candidate
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_candidate;

\echo baseline_run_2
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_base
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_base;

\echo baseline_run_3
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_base
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_base;

\echo candidate_run_3
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_candidate
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_candidate;

\echo candidate_run_4
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_candidate
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_candidate;

\echo baseline_run_4
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_base
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_base;

\echo baseline_run_5
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_base
SELECT * FROM exp018_allocation_write_seed;
TRUNCATE TABLE exp018_allocation_write_base;

\echo candidate_run_5
EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
INSERT INTO exp018_allocation_write_candidate
SELECT * FROM exp018_allocation_write_seed;

\echo candidate_index_bytes
SELECT pg_relation_size('exp018_allocation_write_seller_journal_idx');
