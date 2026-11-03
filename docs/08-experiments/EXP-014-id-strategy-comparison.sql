-- EXP-014: local, report-only PostgreSQL 18 ID strategy comparison.
-- SAFETY: refuses every database except atlas_id_bench before any destructive DDL.
-- Run with psql -X -v ON_ERROR_STOP=1 -d atlas_id_bench -f this-file.sql.
-- Only the dedicated exp014_id_strategy schema is dropped/recreated.
\set ON_ERROR_STOP on
\timing on

DO $guard$
BEGIN
  IF current_database() <> 'atlas_id_bench' THEN
    RAISE EXCEPTION 'EXP-014 refused: connected to %, expected atlas_id_bench', current_database();
  END IF;
END
$guard$;

DROP SCHEMA IF EXISTS exp014_id_strategy CASCADE;
CREATE SCHEMA exp014_id_strategy;
SET search_path = exp014_id_strategy, pg_catalog;

-- Fixed, shared logical sample. Generation is setup, not part of strategy timings.
-- payload is identical across strategies; children have the same count and reference mix.
CREATE TABLE seed (
  ord bigint PRIMARY KEY,
  v4 uuid NOT NULL,
  v7 uuid NOT NULL,
  payload text NOT NULL
);
INSERT INTO seed (ord, v4, v7, payload)
SELECT g,
       uuidv4(),
       uuidv7(),
       repeat(md5(g::text), 8) -- 256 ASCII bytes per parent payload
FROM generate_series(1, 100000) AS g;

CREATE TABLE parent_v4 (
  id uuid PRIMARY KEY,
  payload text NOT NULL
);
CREATE TABLE child_v4 (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id uuid NOT NULL REFERENCES parent_v4(id),
  payload text NOT NULL
);

CREATE TABLE parent_v7 (
  id uuid PRIMARY KEY,
  payload text NOT NULL
);
CREATE TABLE child_v7 (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id uuid NOT NULL REFERENCES parent_v7(id),
  payload text NOT NULL
);

CREATE TABLE parent_bigint (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id uuid NOT NULL UNIQUE,
  payload text NOT NULL
);
CREATE TABLE child_bigint (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id bigint NOT NULL REFERENCES parent_bigint(id),
  payload text NOT NULL
);
-- Same child-FK index for every strategy; index creation is setup, not timed insertion.
CREATE INDEX child_v4_parent_id_idx ON child_v4(parent_id);
CREATE INDEX child_v7_parent_id_idx ON child_v7(parent_id);
CREATE INDEX child_bigint_parent_id_idx ON child_bigint(parent_id);

CREATE TABLE measurements (
  repetition integer NOT NULL,
  strategy text NOT NULL,
  parent_insert_ms double precision NOT NULL,
  child_insert_ms double precision NOT NULL,
  strategy_order integer NOT NULL,
  PRIMARY KEY (repetition, strategy)
);

-- Five measured repetitions; strategy order is independently shuffled each round.
-- Every trial truncates only its own parent/child pair and restarts identity.
-- Seed keys/payload are precomputed; insertion timings exclude key generation and transaction commit.
DO $run$
DECLARE
  rep integer;
  strategy text;
  order_no integer;
  ordered_strategies text[];
  t0 timestamptz;
  parent_ms double precision;
  child_ms double precision;
BEGIN
  FOR rep IN 1..5 LOOP
    SELECT array_agg(s ORDER BY random())
      INTO ordered_strategies
      FROM unnest(ARRAY['UUIDv4', 'UUIDv7', 'BIGINT+UUIDv7']) AS s;
    order_no := 0;
    FOREACH strategy IN ARRAY ordered_strategies LOOP
      order_no := order_no + 1;
      IF strategy = 'UUIDv4' THEN
        TRUNCATE child_v4, parent_v4 RESTART IDENTITY;
        t0 := clock_timestamp();
        INSERT INTO parent_v4 (id, payload)
          SELECT v4, payload FROM seed ORDER BY ord;
        parent_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
        t0 := clock_timestamp();
        INSERT INTO child_v4 (parent_id, payload)
          SELECT s.v4, repeat(md5(g::text), 2)
          FROM generate_series(1, 200000) AS g
          JOIN seed s ON s.ord = ((g::bigint * 7919) % 100000) + 1;
        child_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
      ELSIF strategy = 'UUIDv7' THEN
        TRUNCATE child_v7, parent_v7 RESTART IDENTITY;
        t0 := clock_timestamp();
        INSERT INTO parent_v7 (id, payload)
          SELECT v7, payload FROM seed ORDER BY ord;
        parent_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
        t0 := clock_timestamp();
        INSERT INTO child_v7 (parent_id, payload)
          SELECT s.v7, repeat(md5(g::text), 2)
          FROM generate_series(1, 200000) AS g
          JOIN seed s ON s.ord = ((g::bigint * 7919) % 100000) + 1;
        child_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
      ELSE
        TRUNCATE child_bigint, parent_bigint RESTART IDENTITY;
        t0 := clock_timestamp();
        INSERT INTO parent_bigint (id, public_id, payload) OVERRIDING SYSTEM VALUE
          SELECT ord, v7, payload FROM seed ORDER BY ord;
        parent_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
        t0 := clock_timestamp();
        INSERT INTO child_bigint (parent_id, payload)
          SELECT ((g::bigint * 7919) % 100000) + 1, repeat(md5(g::text), 2)
          FROM generate_series(1, 200000) AS g;
        child_ms := extract(epoch FROM clock_timestamp() - t0) * 1000;
      END IF;
      INSERT INTO measurements VALUES (rep, strategy, parent_ms, child_ms, order_no);
    END LOOP;
  END LOOP;
END
$run$;

-- Refresh statistics after the final repetition; ANALYZE is outside insert timings.
ANALYZE parent_v4;
ANALYZE child_v4;
ANALYZE parent_v7;
ANALYZE child_v7;
ANALYZE parent_bigint;
ANALYZE child_bigint;

-- Counts and derived parent/combined insertion rates (rates are calculation, not separate samples).
SELECT repetition, strategy, strategy_order,
       round(parent_insert_ms::numeric, 2) AS parent_insert_ms,
       round(100000000::numeric / NULLIF(parent_insert_ms::numeric, 0), 1) AS parent_rows_per_second,
       round(child_insert_ms::numeric, 2) AS child_insert_ms,
       round(200000000::numeric / NULLIF(child_insert_ms::numeric, 0), 1) AS child_rows_per_second,
       round(300000000::numeric / NULLIF((parent_insert_ms + child_insert_ms)::numeric, 0), 1) AS combined_rows_per_second
FROM measurements
ORDER BY repetition, strategy_order;
-- Keep raw runs above; these are the across-run medians, not replacements for run data.
SELECT strategy,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY parent_insert_ms))::numeric, 2) AS median_parent_insert_ms,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY child_insert_ms))::numeric, 2) AS median_child_insert_ms,
       round((percentile_cont(0.5) WITHIN GROUP
         (ORDER BY (300000000::double precision / NULLIF(parent_insert_ms + child_insert_ms, 0))))::numeric, 1) AS median_combined_rows_per_second
FROM measurements
GROUP BY strategy
ORDER BY strategy;

-- PostgreSQL version/settings to capture with each execution.
SELECT version() AS postgres_version,
       current_setting('shared_buffers') AS shared_buffers,
       current_setting('effective_cache_size') AS effective_cache_size,
       current_setting('max_parallel_workers_per_gather') AS max_parallel_workers_per_gather,
       current_setting('synchronous_commit') AS synchronous_commit,
       current_setting('track_io_timing') AS track_io_timing;

-- Table heap/index measurements after the final repetition; sizes include the indexed FK columns.
SELECT n.nspname || '.' || c.relname AS relation,
       pg_relation_size(c.oid) AS heap_bytes,
       pg_indexes_size(c.oid) AS index_bytes,
       pg_total_relation_size(c.oid) AS total_bytes
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'exp014_id_strategy'
  AND c.relname IN ('parent_v4', 'child_v4', 'parent_v7', 'child_v7', 'parent_bigint', 'child_bigint')
  AND c.relkind IN ('r', 'p')
ORDER BY c.relname;

-- Lookup: use the third seeded public ID so the strategy sees the same key.
-- Compare EXPLAIN ANALYZE execution time and buffer counts; run each query in this section.
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*) FROM parent_v4 WHERE id IN (SELECT v4 FROM seed WHERE ord % 50 = 3);
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*) FROM parent_v7 WHERE id IN (SELECT v7 FROM seed WHERE ord % 50 = 3);
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*) FROM parent_bigint WHERE public_id IN (SELECT v7 FROM seed WHERE ord % 50 = 3);

-- Child-FK join: same selected 2,000 child rows and identical projected parent payload.
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*), sum(length(p.payload))
FROM child_v4 c JOIN parent_v4 p ON p.id = c.parent_id
WHERE c.id BETWEEN 50001 AND 52000;
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*), sum(length(p.payload))
FROM child_v7 c JOIN parent_v7 p ON p.id = c.parent_id
WHERE c.id BETWEEN 50001 AND 52000;
EXPLAIN (ANALYZE, BUFFERS, TIMING)
SELECT count(*), sum(length(p.payload))
FROM child_bigint c JOIN parent_bigint p ON p.id = c.parent_id
WHERE c.id BETWEEN 50001 AND 52000;
-- Set-based join over the shared bounded sample, not per-key point probes.
-- This may use a sequential/hash plan; do not interpret as point-lookup
-- latency or per-ID index locality. See EXPLAIN snapshots below.
CREATE TEMP TABLE lookup_sample ON COMMIT PRESERVE ROWS AS
SELECT ord, v4, v7
FROM seed
WHERE ord % 50 = 3
ORDER BY random();
ALTER TABLE lookup_sample ADD PRIMARY KEY (ord);
ANALYZE lookup_sample;

CREATE TEMP TABLE lookup_timings (
  repetition integer NOT NULL,
  strategy text NOT NULL,
  elapsed_ms double precision NOT NULL,
  rows_returned bigint NOT NULL,
  PRIMARY KEY (repetition, strategy)
);

DO $lookup$
DECLARE
  strategy text;
  run_order text[];
  repetition integer;
  started_at timestamptz;
  elapsed double precision;
  result_rows bigint;
BEGIN
  -- Warm each alternative once; do not record its timing.
  FOREACH strategy IN ARRAY ARRAY['UUIDv4', 'UUIDv7', 'BIGINT+UUIDv7'] LOOP
    IF strategy = 'UUIDv4' THEN
      PERFORM count(*) FROM lookup_sample s
        JOIN parent_v4 p ON p.id = s.v4
        JOIN child_v4 c ON c.parent_id = p.id;
    ELSIF strategy = 'UUIDv7' THEN
      PERFORM count(*) FROM lookup_sample s
        JOIN parent_v7 p ON p.id = s.v7
        JOIN child_v7 c ON c.parent_id = p.id;
    ELSE
      PERFORM count(*) FROM lookup_sample s
        JOIN parent_bigint p ON p.public_id = s.v7
        JOIN child_bigint c ON c.parent_id = p.id;
    END IF;
  END LOOP;

  FOR repetition IN 1..5 LOOP
    SELECT array_agg(u.strategy ORDER BY random())
      INTO run_order
      FROM unnest(ARRAY['UUIDv4', 'UUIDv7', 'BIGINT+UUIDv7']) AS u(strategy);
    FOREACH strategy IN ARRAY run_order LOOP
      started_at := clock_timestamp();
      IF strategy = 'UUIDv4' THEN
        SELECT count(*) INTO result_rows FROM lookup_sample s
          JOIN parent_v4 p ON p.id = s.v4
          JOIN child_v4 c ON c.parent_id = p.id;
      ELSIF strategy = 'UUIDv7' THEN
        SELECT count(*) INTO result_rows FROM lookup_sample s
          JOIN parent_v7 p ON p.id = s.v7
          JOIN child_v7 c ON c.parent_id = p.id;
      ELSE
        SELECT count(*) INTO result_rows FROM lookup_sample s
          JOIN parent_bigint p ON p.public_id = s.v7
          JOIN child_bigint c ON c.parent_id = p.id;
      END IF;
      elapsed := 1000 * extract(epoch FROM clock_timestamp() - started_at);
      INSERT INTO lookup_timings VALUES (repetition, strategy, elapsed, result_rows);
    END LOOP;
  END LOOP;
END
$lookup$;

-- PL/pgSQL loop/dispatch overhead is included equally; client round-trips are
-- excluded. Result rows are child rows joined for the fixed 2,000 public IDs.
-- Preserve all five randomized query runs; these rates are derived from returned rows.
SELECT repetition, strategy,
       round(elapsed_ms::numeric, 2) AS elapsed_ms,
       rows_returned,
       round(1000 * rows_returned::numeric / NULLIF(elapsed_ms::numeric, 0), 1) AS rows_per_second
FROM lookup_timings
ORDER BY repetition, strategy;

SELECT strategy,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY elapsed_ms))::numeric, 2) AS median_elapsed_ms,
       round((percentile_cont(0.5) WITHIN GROUP
         (ORDER BY (1000 * rows_returned::double precision / NULLIF(elapsed_ms, 0))))::numeric, 1) AS median_rows_per_second
FROM lookup_timings
GROUP BY strategy
ORDER BY strategy;

-- Keep the schema for inspection. Optional cleanup, only after confirming this is atlas_id_bench:
-- DROP SCHEMA exp014_id_strategy CASCADE;
