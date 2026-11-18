-- Keyword search on the public catalog listing used
-- `position(keyword in lower(name)) > 0`, which the planner can only evaluate as a
-- filter while walking `product_listing_sort_idx` in listing order. For a selective
-- keyword that meant scanning the whole table before LIMIT rows matched: 409 ms over
-- 100k products, and it is the slowest route in the EXP-035 read workload.
--
-- The query now uses `lower(name) LIKE '%keyword%'`, which pg_trgm can serve from a
-- GIN index. The predicate is unchanged semantically (the service already trims and
-- lowercases the keyword, and wildcards are escaped in the query).
--
-- Requires the pg_trgm extension. On a managed PostgreSQL where the application role
-- cannot CREATE EXTENSION, a DBA must install it before this migration runs.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- The operator class lives in whichever schema owns the extension, and Flyway runs
-- migrations with `search_path` set to the target schema. Testcontainers migration
-- tests create per-test schemas, so an unqualified `gin_trgm_ops` fails there with
-- "operator class does not exist". Resolve the owning schema and qualify it.
DO $$
DECLARE
    trgm_schema text;
BEGIN
    SELECT n.nspname
    INTO trgm_schema
    FROM pg_extension e
    JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'pg_trgm';

    IF trgm_schema IS NULL THEN
        RAISE EXCEPTION 'pg_trgm extension is required for catalog keyword search';
    END IF;

    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS product_name_trgm_idx '
        'ON product USING gin (lower(name) %I.gin_trgm_ops)',
        trgm_schema);
END
$$;
