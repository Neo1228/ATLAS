package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalBalanceReportDispositionResponse;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary.DailyTrend;
import com.atlas.application.dto.response.PayPalBalanceReportRowResponse;
import com.atlas.application.dto.response.PayPalBalanceReportRunResponse;
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

/** PayPal balance report 대사 결과의 운영자용 읽기 모델. 쓰기는 settlement 모듈의 store가 소유한다. */
@Repository
public class PayPalBalanceReportQueryRepository {
    private static final RowMapper<PayPalBalanceReportRunResponse> RUN_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportRunResponse(rs.getObject("run_id", UUID.class), rs.getString("report_sha256"),
                    rs.getTimestamp("uploaded_at").toInstant(), rs.getString("report_format"),
                    rs.getInt("row_count"), rs.getInt("matched_count"), rs.getInt("unmatched_count"),
                    rs.getInt("mismatch_count"), rs.getInt("duplicate_count"));
    private static final RowMapper<PayPalBalanceReportRowResponse> ROW_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportRowResponse(rs.getObject("run_id", UUID.class), rs.getInt("row_number"),
                    rs.getString("record_id"), rs.getString("related_record_id"), rs.getString("record_type"),
                    rs.getString("status"), rs.getString("reason_code"));
    private static final RowMapper<PayPalBalanceReportDispositionResponse> DISPOSITION_MAPPER = (rs, rowNum) ->
            new PayPalBalanceReportDispositionResponse(rs.getObject("disposition_id", UUID.class),
                    rs.getObject("run_id", UUID.class), rs.getInt("row_number"), rs.getString("disposition"),
                    rs.getString("actor"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PayPalBalanceReportQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 최근 30일(오늘 포함) 일별 UNMATCHED/MISMATCH 합계. 업로드가 없는 날은 0으로 채운다. */
    public List<DailyTrend> dailyTrend() {
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
                """, (rs, rowNum) -> new DailyTrend(rs.getDate("report_day").toLocalDate(),
                rs.getLong("unmatched"), rs.getLong("mismatch")));
    }

    /** 운영자 처리(disposition)가 아직 없는 예외 행 수. */
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

    public List<PayPalBalanceReportRunResponse> listRuns(int fetchLimit, int offset) {
        return jdbc.query("""
                SELECT run_id, report_sha256, uploaded_at, report_format, row_count, matched_count,
                       unmatched_count, mismatch_count, duplicate_count
                FROM paypal_balance_report_run
                ORDER BY uploaded_at DESC, run_id DESC
                LIMIT ? OFFSET ?
                """, RUN_MAPPER, fetchLimit, offset);
    }

    public Optional<PayPalBalanceReportRunResponse> findRunByReportSha256(String reportSha256) {
        return jdbc.query("""
                SELECT run_id, report_sha256, uploaded_at, report_format, row_count, matched_count,
                       unmatched_count, mismatch_count, duplicate_count
                FROM paypal_balance_report_run WHERE report_sha256 = ?
                """, RUN_MAPPER, reportSha256).stream().findFirst();
    }

    /** 모든 run의 예외 행. {@code status}, 업로드 기간, {@code recordId}는 null이면 조건에서 제외한다. */
    public List<PayPalBalanceReportRowResponse> listExceptions(String status, LocalDate from, LocalDate to,
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
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public List<PayPalBalanceReportRowResponse> listRunRows(UUID runId, String status, String recordId,
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
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public List<PayPalBalanceReportDispositionResponse> dispositionHistory(UUID runId, int rowNumber, int limit) {
        return jdbc.query("""
                SELECT disposition_id, run_id, row_number, disposition, actor, reason, created_at
                FROM paypal_balance_report_disposition
                WHERE run_id = ? AND row_number = ?
                ORDER BY created_at ASC, disposition_id ASC
                LIMIT ?
                """, DISPOSITION_MAPPER, runId, rowNumber, limit);
    }

    public Optional<PayPalBalanceReportDispositionResponse> findDispositionByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("""
                SELECT disposition_id, run_id, row_number, disposition, actor, reason, created_at
                FROM paypal_balance_report_disposition WHERE idempotency_key = ?
                """, DISPOSITION_MAPPER, idempotencyKey).stream().findFirst();
    }

    public Optional<String> findRowStatus(UUID runId, int rowNumber) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT status FROM paypal_balance_report_row WHERE run_id = ? AND row_number = ?",
                    String.class, runId, rowNumber));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
