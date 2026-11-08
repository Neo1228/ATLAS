\set ON_ERROR_STOP on
\if :{?phase}
\else
\echo 'Refusing: pass -v phase=fixture or -v phase=write'
\quit 3
\endif

SELECT current_database() = 'atlas_query_evidence' AS exp020_database \gset
\if :exp020_database
\else
\echo 'Refusing: database must be atlas_query_evidence'
\quit 3
\endif

-- Require the exact EXP-018 100k base fixture; never delete or truncate.
SELECT
    (SELECT COUNT(*) FROM settlement_journal
     WHERE journal_id IN (SELECT md5('exp018-journal-' || n)::uuid FROM generate_series(1, 100000) AS n)) = 100000
    AND (SELECT COUNT(*) FROM settlement_seller_allocation
     WHERE journal_id IN (SELECT md5('exp018-journal-' || n)::uuid FROM generate_series(1, 100000) AS n)) = 100000
    AND (SELECT COUNT(*) FROM settlement_entry
     WHERE journal_id IN (SELECT md5('exp018-journal-' || n)::uuid FROM generate_series(1, 100000) AS n)) = 200000
    AS exp018_seed_ready \gset
\if :exp018_seed_ready
\else
\echo 'Refusing: exact EXP-018 100k seed is not present'
\quit 3
\endif

SELECT :'phase' = 'fixture' AS phase_fixture, :'phase' = 'write' AS phase_write \gset
\if :phase_fixture
    SELECT NOT EXISTS (
        SELECT 1 FROM settlement_journal
        WHERE journal_id IN (md5('exp020-direct-capture')::uuid, md5('exp020-refund-reversal')::uuid)
    ) AND NOT EXISTS (
        SELECT 1 FROM settlement_entry
        WHERE journal_id IN (md5('exp020-direct-capture')::uuid, md5('exp020-refund-reversal')::uuid)
    ) AS fixture_ids_unused \gset
    \if :fixture_ids_unused
    \else
    \echo 'Refusing: EXP-020 fixture rows already exist'
    \quit 3
    \endif

    BEGIN;
    INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id,
                                    gross_amount_won, recorded_at)
    VALUES (md5('exp020-direct-capture')::uuid, md5('exp018-order-1')::uuid,
            md5('exp020-direct-attempt')::uuid, md5('exp018-seller-1')::uuid,
            150, TIMESTAMPTZ '2026-01-02 00:00:00+00');
    INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
    VALUES (md5('exp020-direct-capture')::uuid, 'PSP_CLEARING', 'DEBIT', 150,
            TIMESTAMPTZ '2026-01-02 00:00:00+00'),
           (md5('exp020-direct-capture')::uuid, 'SELLER_PAYABLE', 'CREDIT', 150,
            TIMESTAMPTZ '2026-01-02 00:00:00+00');

    INSERT INTO settlement_journal (journal_id, source_order_id, source_refund_id, seller_id,
                                    gross_amount_won, recorded_at)
    VALUES (md5('exp020-refund-reversal')::uuid, md5('exp018-order-1')::uuid,
            md5('exp020-refund-id')::uuid, md5('exp018-seller-1')::uuid,
            50, TIMESTAMPTZ '2026-01-02 00:00:01+00');
    INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
    VALUES (md5('exp020-refund-reversal')::uuid, 'PSP_CLEARING', 'CREDIT', 50,
            TIMESTAMPTZ '2026-01-02 00:00:01+00'),
           (md5('exp020-refund-reversal')::uuid, 'SELLER_PAYABLE', 'DEBIT', 50,
            TIMESTAMPTZ '2026-01-02 00:00:01+00');
    COMMIT;
    ANALYZE settlement_journal;

    SELECT COUNT(*) AS exp020_fixture_journals
    FROM settlement_journal
    WHERE journal_id IN (md5('exp020-direct-capture')::uuid, md5('exp020-refund-reversal')::uuid);
\elif :phase_write
    \if :{?run_no}
    \else
    \echo 'Refusing: phase=write requires -v run_no=1, 2, or 3'
    \quit 3
    \endif
    SELECT :'run_no'::integer BETWEEN 1 AND 3 AS exp020_valid_run \gset
    \if :exp020_valid_run
    \else
    \echo 'Refusing: run_no must be 1, 2, or 3'
    \quit 3
    \endif
    SELECT COUNT(*) = 2 AS exp020_fixture_ready
    FROM settlement_journal
    WHERE journal_id IN (md5('exp020-direct-capture')::uuid, md5('exp020-refund-reversal')::uuid) \gset
    \if :exp020_fixture_ready
    \else
    \echo 'Refusing: phase=fixture must complete before phase=write'
    \quit 3
    \endif
    SELECT NOT EXISTS (
        SELECT 1 FROM settlement_journal
        WHERE source_attempt_id IN (
            SELECT md5('exp020-write-' || :'run_no' || '-attempt-' || n)::uuid
            FROM generate_series(1, 10000) AS n)
    ) AS exp020_write_ids_unused \gset
    \if :exp020_write_ids_unused
    \else
    \echo 'Refusing: write batch IDs for this run_no already exist'
    \quit 3
    \endif

\timing on
    BEGIN;
    EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
    INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id,
                                    gross_amount_won, recorded_at)
    SELECT md5('exp020-write-' || :'run_no' || '-journal-' || n)::uuid,
           md5('exp018-order-' || ((:'run_no'::integer - 1) * 10000 + n))::uuid,
           md5('exp020-write-' || :'run_no' || '-attempt-' || n)::uuid,
           md5('exp018-seller-1')::uuid, 100,
           TIMESTAMPTZ '2026-01-04 00:00:00+00'
             + ((:'run_no'::integer - 1) * 10000 + n) * INTERVAL '1 second'
    FROM generate_series(1, 10000) AS n;

    EXPLAIN (ANALYZE, BUFFERS, WAL, SETTINGS, FORMAT JSON)
    INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
    SELECT md5('exp020-write-' || :'run_no' || '-journal-' || n)::uuid,
           'PSP_CLEARING', 'DEBIT', 100,
           TIMESTAMPTZ '2026-01-04 00:00:00+00'
             + ((:'run_no'::integer - 1) * 10000 + n) * INTERVAL '1 second'
    FROM generate_series(1, 10000) AS n
    UNION ALL
    SELECT md5('exp020-write-' || :'run_no' || '-journal-' || n)::uuid,
           'SELLER_PAYABLE', 'CREDIT', 100,
           TIMESTAMPTZ '2026-01-04 00:00:00+00'
             + ((:'run_no'::integer - 1) * 10000 + n) * INTERVAL '1 second'
    FROM generate_series(1, 10000) AS n;
    COMMIT;
\timing off

    SELECT COUNT(*) AS exp020_write_journals
    FROM settlement_journal
    WHERE source_attempt_id IN (
        SELECT md5('exp020-write-' || :'run_no' || '-attempt-' || n)::uuid
        FROM generate_series(1, 10000) AS n);
\else
\echo 'Refusing: phase must be fixture or write'
\quit 3
\endif
