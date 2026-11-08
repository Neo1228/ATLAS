\set ON_ERROR_STOP on

-- Required variables:
--   -v expected_arm=baseline|candidate
--   -v expected_phase=prepare|snapshot
--   -v expected_database=atlas_app_write_evidence_<RUN_ID>
-- Use only an independently created, disposable, run-owned database/container/network.
\if :{?expected_arm}
\else
  \echo 'Refusing EXP-022: expected_arm=baseline|candidate is required'
  \quit 3
\endif
\if :{?expected_phase}
\else
  \echo 'Refusing EXP-022: expected_phase=prepare|snapshot is required'
  \quit 3
\endif
\if :{?expected_database}
\else
  \echo 'Refusing EXP-022: exact expected_database is required'
  \quit 3
\endif

SELECT :'expected_arm' IN ('baseline', 'candidate') AS exp022_arm_valid,
       :'expected_phase' IN ('prepare', 'snapshot') AS exp022_phase_valid,
       current_database() = :'expected_database'
           AND :'expected_database' ~ '^atlas_app_write_evidence_[A-Za-z0-9_-]+$'
           AS exp022_database_valid,
       :'expected_arm' = 'candidate' AS exp022_is_candidate,
       :'expected_phase' = 'prepare' AS exp022_is_prepare
\gset

\if :exp022_arm_valid
\else
  \echo 'Refusing EXP-022: expected_arm must be baseline or candidate'
  \quit 3
\endif
\if :exp022_phase_valid
\else
  \echo 'Refusing EXP-022: expected_phase must be prepare or snapshot'
  \quit 3
\endif
\if :exp022_database_valid
\else
  \echo 'Refusing EXP-022: expected_database must exactly match current_database() and the run-name pattern'
  \quit 3
\endif

-- Prepare creates the candidate index only once; baseline asserts its absence.
-- Snapshot re-checks that each arm still has the intended index state.
\if :exp022_is_prepare
  SELECT NOT EXISTS (SELECT 1 FROM settlement_journal)
      AND NOT EXISTS (SELECT 1 FROM settlement_entry) AS exp022_ledger_empty
  \gset
  \if :exp022_ledger_empty
  \else
    \echo 'Refusing EXP-022 prepare: settlement ledger must be empty before measured writes'
    \quit 3
  \endif

  SELECT to_regclass('public.exp022_candidate_recorded_journal_idx') IS NULL
      AS exp022_index_absent
  \gset
  \if :exp022_index_absent
    \if :exp022_is_candidate
      CREATE INDEX exp022_candidate_recorded_journal_idx
        ON settlement_journal (recorded_at, journal_id);
    \else
      \echo 'EXP-022 baseline: candidate index absent'
    \endif
  \else
    \echo 'Refusing EXP-022 prepare: candidate index already exists; use a fresh run-owned database'
    \quit 3
  \endif
  ANALYZE settlement_journal;
  ANALYZE settlement_entry;
\else
  SELECT CASE
           WHEN :'expected_arm' = 'candidate'
             THEN to_regclass('public.exp022_candidate_recorded_journal_idx') IS NOT NULL
           ELSE to_regclass('public.exp022_candidate_recorded_journal_idx') IS NULL
         END AS exp022_index_state_valid
  \gset
  \if :exp022_index_state_valid
    \echo 'EXP-022 snapshot: index state matches expected arm'
  \else
    \echo 'Refusing EXP-022 snapshot: candidate index state does not match expected arm'
    \quit 3
  \endif
\endif

-- Run once with expected_phase=prepare after app fixture setup, before measured writes;
-- run again with expected_phase=snapshot after measured writes. These are snapshots,
-- not transaction or commit durations.
SELECT current_database() AS database_name,
       clock_timestamp() AS observed_at,
       pg_current_wal_lsn() AS wal_lsn,
       pg_database_size(current_database()) AS database_bytes,
       pg_total_relation_size('settlement_journal') AS journal_total_bytes,
       pg_relation_size('settlement_journal') AS journal_heap_bytes,
       pg_indexes_size('settlement_journal') AS journal_all_index_bytes,
       CASE
         WHEN to_regclass('public.exp022_candidate_recorded_journal_idx') IS NULL THEN NULL
         ELSE pg_relation_size(to_regclass('public.exp022_candidate_recorded_journal_idx'))
       END AS candidate_index_bytes,
       (SELECT idx_scan
          FROM pg_stat_user_indexes
         WHERE relname = 'settlement_journal'
           AND indexrelname = 'exp022_candidate_recorded_journal_idx') AS candidate_index_scans;

-- V10 schema: one debit and one credit entry per capture journal.
SELECT (SELECT count(*) FROM settlement_journal) AS journal_rows,
       (SELECT count(*) FROM settlement_entry) AS entry_rows,
       (SELECT count(*)
          FROM (
            SELECT j.journal_id
              FROM settlement_journal AS j
              LEFT JOIN settlement_entry AS e USING (journal_id)
             GROUP BY j.journal_id
            HAVING count(e.entry_id) <> 2
          ) AS wrong_entry_counts) AS journals_not_exactly_two_entries,
       (SELECT count(*)
          FROM (
            SELECT journal_id
              FROM settlement_entry
             GROUP BY journal_id
            HAVING COALESCE(sum(amount_won) FILTER (WHERE entry_side = 'DEBIT'), 0)
                <> COALESCE(sum(amount_won) FILTER (WHERE entry_side = 'CREDIT'), 0)
          ) AS unbalanced) AS unbalanced_journals;

-- Cleanup is intentionally not automated. Drop only independently proven run-owned resources.
