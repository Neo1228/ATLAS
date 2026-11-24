package com.atlas.settlement.repository;

import com.atlas.settlement.service.PayPalBalanceReportStore;
import com.atlas.settlement.service.PayPalBalanceReportDailyTrend;
import com.atlas.settlement.service.PayPalBalanceReportDisposition;
import com.atlas.settlement.service.PayPalBalanceReportExceptionRow;
import com.atlas.settlement.service.PayPalBalanceReportRow;
import com.atlas.settlement.service.PayPalBalanceReportRun;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PayPal 보고서 사실과 disposition 감사 이력의 저장과 운영자 조회를 소유합니다. 이 테이블들의 SQL은 여기에만 둔다. */
@Repository
public class JdbcPayPalBalanceReportStore implements PayPalBalanceReportStore {
    private static final RowMapper<PayPalBalanceReportRun> RUN_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportRun(rs.getObject("run_id", UUID.class), rs.getString("report_sha256"),
                    rs.getTimestamp("uploaded_at").toInstant(), rs.getString("report_format"), rs.getInt("row_count"),
                    rs.getInt("matched_count"), rs.getInt("unmatched_count"), rs.getInt("mismatch_count"),
                    rs.getInt("duplicate_count"));

    private static final RowMapper<PayPalBalanceReportRow> ROW_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportRow(rs.getObject("run_id", UUID.class), rs.getInt("row_number"),
                    rs.getString("record_id"), rs.getString("related_record_id"), rs.getString("original_record_id"),
                    rs.getString("record_type"), rs.getString("record_subtype"), rs.getString("currency"),
                    rs.getBigDecimal("gross_amount"), rs.getBigDecimal("fee_amount"), rs.getBigDecimal("net_amount"),
                    rs.getString("transfer_id"), rs.getString("status"), rs.getString("reason_code"));

    private static final RowMapper<PayPalBalanceReportDisposition> DISPOSITION_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportDisposition(rs.getObject("disposition_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getInt("row_number"), rs.getString("disposition"),
                    rs.getString("actor"), rs.getString("reason"), rs.getString("idempotency_key"),
                    rs.getTimestamp("created_at").toInstant());

    private static final String RUN_COLUMNS = "run_id, report_sha256, uploaded_at, report_format, row_count, "
            + "matched_count, unmatched_count, mismatch_count, duplicate_count";
    private static final String ROW_COLUMNS = "run_id, row_number, record_id, related_record_id, original_record_id, "
            + "record_type, record_subtype, currency, gross_amount, fee_amount, net_amount, transfer_id, status, reason_code";
    private static final String DISPOSITION_COLUMNS = "disposition_id, run_id, row_number, disposition, actor, reason, "
            + "idempotency_key, created_at";

    private final JdbcTemplate jdbc;

    public JdbcPayPalBalanceReportStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** run과 행을 원자적으로 추가하며, 같은 SHA-256은 기존 run을 재사용합니다. */
    @Transactional
    @Override
    public Optional<UUID> createRunIfAbsent(PayPalBalanceReportRun run, List<PayPalBalanceReportRow> rows) {
        Objects.requireNonNull(run, "run");
        List<PayPalBalanceReportRow> reportRows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (reportRows.size() != run.rowCount()) {
            throw new IllegalArgumentException("rowCount must equal the number of supplied rows");
        }
        int matched = 0;
        int unmatched = 0;
        int mismatch = 0;
        int duplicate = 0;
        for (PayPalBalanceReportRow row : reportRows) {
            if (!run.runId().equals(row.runId())) {
                throw new IllegalArgumentException("Each row must belong to the inserted run");
            }
            switch (row.status()) {
                case "MATCHED" -> matched++;
                case "UNMATCHED" -> unmatched++;
                case "MISMATCH" -> mismatch++;
                case "DUPLICATE" -> duplicate++;
                default -> throw new IllegalArgumentException("Unsupported row status: " + row.status());
            }
        }
        if (matched != run.matchedCount() || unmatched != run.unmatchedCount()
                || mismatch != run.mismatchCount() || duplicate != run.duplicateCount()) {
            throw new IllegalArgumentException("Run outcome counts must equal row statuses");
        }
        int inserted = jdbc.update("""
                INSERT INTO paypal_balance_report_run
                    (run_id, report_sha256, uploaded_at, report_format, row_count,
                     matched_count, unmatched_count, mismatch_count, duplicate_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (report_sha256) DO NOTHING
                """, run.runId(), run.reportSha256(), Timestamp.from(run.uploadedAt()), run.reportFormat(),
                run.rowCount(), run.matchedCount(), run.unmatchedCount(), run.mismatchCount(), run.duplicateCount());
        if (inserted == 0) {
            return Optional.empty();
        }
        if (!reportRows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO paypal_balance_report_row
                        (run_id, row_number, record_id, related_record_id, original_record_id, record_type,
                         record_subtype, currency, gross_amount, fee_amount, net_amount, transfer_id, status, reason_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, reportRows, reportRows.size(), (ps, row) -> {
                ps.setObject(1, row.runId());
                ps.setInt(2, row.rowNumber());
                ps.setString(3, row.recordId());
                ps.setString(4, row.relatedRecordId());
                ps.setString(5, row.originalRecordId());
                ps.setString(6, row.recordType());
                ps.setString(7, row.recordSubtype());
                ps.setString(8, row.currency());
                ps.setBigDecimal(9, row.grossAmount());
                ps.setBigDecimal(10, row.feeAmount());
                ps.setBigDecimal(11, row.netAmount());
                ps.setString(12, row.transferId());
                ps.setString(13, row.status());
                ps.setString(14, row.reasonCode());
            });
        }
        return Optional.of(run.runId());
    }

    @Override
    public Optional<PayPalBalanceReportRun> findRunById(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM paypal_balance_report_run WHERE run_id = ?",
                RUN_MAPPER, runId).stream().findFirst();
    }

    @Override
    public Optional<PayPalBalanceReportRun> findRunBySha256(String reportSha256) {
        Objects.requireNonNull(reportSha256, "reportSha256");
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM paypal_balance_report_run WHERE report_sha256 = ?",
                RUN_MAPPER, reportSha256).stream().findFirst();
    }

    /** 최근 run부터 한 페이지를 조회한다. 같은 시각의 run은 run_id 내림차순으로 정렬해 페이지가 흔들리지 않게 한다. */
    @Override
    public List<PayPalBalanceReportRun> listRuns(int limit, int offset) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM paypal_balance_report_run "
                + "ORDER BY uploaded_at DESC, run_id DESC LIMIT ? OFFSET ?", RUN_MAPPER, limit, offset);
    }

    /** 파서가 허용하는 최대 10,000개 행을 순서대로 조회합니다. */
    @Override
    public List<PayPalBalanceReportRow> listAllRows(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM paypal_balance_report_row "
                + "WHERE run_id = ? ORDER BY row_number LIMIT 10000", ROW_MAPPER, runId);
    }


    /** idempotency key가 없을 때 disposition을 추가합니다. */
    @Transactional
    @Override
    public Optional<UUID> insertDispositionIfAbsent(PayPalBalanceReportDisposition disposition) {
        Objects.requireNonNull(disposition, "disposition");
        int inserted = jdbc.update("""
                INSERT INTO paypal_balance_report_disposition
                    (disposition_id, run_id, row_number, disposition, actor, reason, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, disposition.dispositionId(), disposition.runId(), disposition.rowNumber(),
                disposition.disposition(), disposition.actor(), disposition.reason(), disposition.idempotencyKey(),
                Timestamp.from(disposition.createdAt()));
        return inserted == 0 ? Optional.empty() : Optional.of(disposition.dispositionId());
    }

    @Override
    public Optional<PayPalBalanceReportDisposition> findDispositionByIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return jdbc.query("SELECT " + DISPOSITION_COLUMNS
                + " FROM paypal_balance_report_disposition WHERE idempotency_key = ?",
                DISPOSITION_MAPPER, idempotencyKey).stream().findFirst();
    }

    /** run과 행별 disposition 이력을 오래된 순서로 최대 limit건 조회합니다. */
    @Override
    public List<PayPalBalanceReportDisposition> findDispositionHistory(UUID runId, int rowNumber, int limit) {
        Objects.requireNonNull(runId, "runId");
        if (rowNumber < 1 || rowNumber > 10_000) {
            throw new IllegalArgumentException("rowNumber must be between 1 and 10000");
        }
        return jdbc.query("SELECT " + DISPOSITION_COLUMNS + " FROM paypal_balance_report_disposition "
                + "WHERE run_id = ? AND row_number = ? ORDER BY created_at ASC, disposition_id ASC LIMIT ?",
                DISPOSITION_MAPPER, runId, rowNumber, limit);
    }

    /** 최근 30일(오늘 포함) 일별 UNMATCHED/MISMATCH 합계. 업로드가 없는 날은 0으로 채운다. */
    @Override
    public List<PayPalBalanceReportDailyTrend> dailyTrend() {
        return jdbc.query("""
                WITH days AS (
                    SELECT generate_series(CURRENT_DATE - INTERVAL '29 days', CURRENT_DATE,
                                           INTERVAL '1 day')::date AS report_day
                ), daily AS (
                    SELECT uploaded_at::date AS report_day, SUM(unmatched_count) AS unmatched,
                           SUM(mismatch_count) AS mismatch
                    FROM paypal_balance_report_run
                    WHERE uploaded_at >= CURRENT_DATE - INTERVAL '29 days'
                      AND uploaded_at < CURRENT_DATE + INTERVAL '1 day'
                    GROUP BY uploaded_at::date
                )
                SELECT days.report_day, COALESCE(daily.unmatched, 0) AS unmatched,
                       COALESCE(daily.mismatch, 0) AS mismatch
                FROM days LEFT JOIN daily USING (report_day)
                ORDER BY days.report_day
                """, (rs, rowNum) -> new PayPalBalanceReportDailyTrend(rs.getDate("report_day").toLocalDate(),
                rs.getLong("unmatched"), rs.getLong("mismatch")));
    }

    /** 운영자 처리(disposition)가 아직 없는 예외 행 수. */
    @Override
    public long countPendingExceptions() {
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM paypal_balance_report_row r
                WHERE r.status IN ('UNMATCHED', 'MISMATCH', 'DUPLICATE')
                  AND NOT EXISTS (
                    SELECT 1 FROM paypal_balance_report_disposition d
                    WHERE d.run_id = r.run_id AND d.row_number = r.row_number
                  )
                """, Long.class);
        return pending == null ? 0 : pending;
    }

    /** 모든 run의 예외 행. {@code status}, 업로드 기간, {@code recordId}는 null이면 조건에서 제외한다. */
    @Override
    public List<PayPalBalanceReportExceptionRow> listExceptions(String status, LocalDate from, LocalDate to,
            String recordId, int fetchLimit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.run_id, r.row_number, r.record_id, r.related_record_id, r.record_type, r.status, r.reason_code
                FROM paypal_balance_report_row r
                JOIN paypal_balance_report_run u ON u.run_id = r.run_id
                WHERE r.status IN ('UNMATCHED', 'MISMATCH', 'DUPLICATE')
                """);
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND u.uploaded_at >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND u.uploaded_at < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        if (recordId != null) {
            sql.append(" AND r.record_id = ?");
            args.add(recordId);
        }
        sql.append(" ORDER BY u.uploaded_at DESC, u.run_id DESC, r.row_number ASC LIMIT ? OFFSET ?");
        args.add(fetchLimit);
        args.add(offset);
        return jdbc.query(sql.toString(), EXCEPTION_ROW_MAPPER, args.toArray());
    }

    @Override
    public List<PayPalBalanceReportExceptionRow> listRunRows(UUID runId, String status, String recordId,
            int fetchLimit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT run_id, row_number, record_id, related_record_id, record_type, status, reason_code
                FROM paypal_balance_report_row WHERE run_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(runId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (recordId != null) {
            sql.append(" AND record_id = ?");
            args.add(recordId);
        }
        sql.append(" ORDER BY row_number ASC LIMIT ? OFFSET ?");
        args.add(fetchLimit);
        args.add(offset);
        return jdbc.query(sql.toString(), EXCEPTION_ROW_MAPPER, args.toArray());
    }

    @Override
    public Optional<String> findRowStatus(UUID runId, int rowNumber) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT status FROM paypal_balance_report_row WHERE run_id = ? AND row_number = ?",
                    String.class, runId, rowNumber));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final RowMapper<PayPalBalanceReportExceptionRow> EXCEPTION_ROW_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportExceptionRow(rs.getObject("run_id", UUID.class), rs.getInt("row_number"),
                    rs.getString("record_id"), rs.getString("related_record_id"), rs.getString("record_type"),
                    rs.getString("status"), rs.getString("reason_code"));
}
