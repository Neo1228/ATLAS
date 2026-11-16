package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalBalanceReportDispositionResponse;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary.DailyTrend;
import com.atlas.application.dto.response.PayPalBalanceReportPage;
import com.atlas.application.dto.response.PayPalBalanceReportRowResponse;
import com.atlas.application.dto.response.PayPalBalanceReportRunResponse;
import com.atlas.settlement.service.PayPalBalanceReportDisposition;
import com.atlas.settlement.service.PayPalBalanceReportStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PayPalBalanceReportOperatorService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE_OFFSET = 10_000;

    private static final int MAX_ROW_NUMBER = 10_000;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final List<String> EXCEPTION_STATUSES = List.of("UNMATCHED", "MISMATCH", "DUPLICATE");
    private static final List<String> ROW_STATUSES = List.of("MATCHED", "UNMATCHED", "MISMATCH", "DUPLICATE");

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
    private final PayPalBalanceReportStore store;

    public PayPalBalanceReportOperatorService(JdbcTemplate jdbc, PayPalBalanceReportStore store) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
    }

    public PayPalBalanceReportOperatorSummary summary() {
        List<DailyTrend> trend = jdbc.query("""
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
        long unmatched = trend.stream().mapToLong(DailyTrend::unmatched).sum();
        long mismatch = trend.stream().mapToLong(DailyTrend::mismatch).sum();
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM paypal_balance_report_row r
                WHERE r.status IN ('UNMATCHED', 'MISMATCH', 'DUPLICATE')
                  AND NOT EXISTS (
                    SELECT 1 FROM paypal_balance_report_disposition d
                    WHERE d.run_id = r.run_id AND d.row_number = r.row_number
                  )
                """, Long.class);
        return new PayPalBalanceReportOperatorSummary(trend, unmatched, mismatch, pending == null ? 0 : pending);
    }

    public PayPalBalanceReportPage<PayPalBalanceReportRunResponse> listRuns(int requestedLimit, int offset) {
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        List<PayPalBalanceReportRunResponse> fetched = jdbc.query("""
                SELECT run_id, report_sha256, uploaded_at, report_format, row_count, matched_count,
                       unmatched_count, mismatch_count, duplicate_count
                FROM paypal_balance_report_run
                ORDER BY uploaded_at DESC, run_id DESC
                LIMIT ? OFFSET ?
                """, RUN_MAPPER, limit + 1, offset);
        return page(fetched, limit, offset);
    }

    public PayPalBalanceReportRunResponse search(String reportSha256) {
        if (reportSha256 == null || !reportSha256.matches("[0-9a-f]{64}")) {
            throw badRequest("reportSha256 must be a lowercase SHA-256 digest");
        }
        return jdbc.query("""
                SELECT run_id, report_sha256, uploaded_at, report_format, row_count, matched_count,
                       unmatched_count, mismatch_count, duplicate_count
                FROM paypal_balance_report_run WHERE report_sha256 = ?
                """, RUN_MAPPER, reportSha256).stream().findFirst()
                .orElseThrow(() -> notFound("Report run not found"));
    }

    public PayPalBalanceReportPage<PayPalBalanceReportRowResponse> listExceptions(String status, LocalDate from,
            LocalDate to, String recordId, int requestedLimit, int offset) {
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        if (status != null && !EXCEPTION_STATUSES.contains(status)) {
            throw badRequest("status must be UNMATCHED, MISMATCH, or DUPLICATE");
        }
        validateDateRange(from, to);
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
        addDateFilters(sql, args, from, to, "u.uploaded_at");
        if (recordId != null) {
            sql.append(" AND r.record_id = ?");
            args.add(recordId);
        }
        sql.append(" ORDER BY u.uploaded_at DESC, u.run_id DESC, r.row_number ASC LIMIT ? OFFSET ?");
        args.add(limit + 1);
        args.add(offset);
        return page(jdbc.query(sql.toString(), ROW_MAPPER, args.toArray()), limit, offset);
    }

    public PayPalBalanceReportPage<PayPalBalanceReportRowResponse> listRunRows(UUID runId, String status,
            String recordId, int requestedLimit, int offset) {
        requireRun(runId);
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        if (status != null && !ROW_STATUSES.contains(status)) {
            throw badRequest("Unsupported row status");
        }
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
        args.add(limit + 1);
        args.add(offset);
        return page(jdbc.query(sql.toString(), ROW_MAPPER, args.toArray()), limit, offset);
    }

    public List<PayPalBalanceReportDispositionResponse> dispositionHistory(UUID runId, int rowNumber) {
        requireRun(runId);
        requireRow(runId, rowNumber);
        return jdbc.query("""
                SELECT disposition_id, run_id, row_number, disposition, actor, reason, created_at
                FROM paypal_balance_report_disposition
                WHERE run_id = ? AND row_number = ?
                ORDER BY created_at ASC, disposition_id ASC
                LIMIT ?
                """, DISPOSITION_MAPPER, runId, rowNumber, MAX_PAGE_SIZE);
    }

    public PayPalBalanceReportDispositionResponse recordDisposition(UUID runId, int rowNumber, String disposition,
            String actor, String reason, String idempotencyKey) {
        requireRun(runId);
        String cleanReason = validateDispositionInput(rowNumber, disposition, actor, reason, idempotencyKey);
        String rowStatus = rowStatus(runId, rowNumber);
        if ("MATCHED".equals(rowStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Matched rows cannot be dispositioned");
        }
        if (!EXCEPTION_STATUSES.contains(rowStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only exception rows can be dispositioned");
        }

        PayPalBalanceReportDisposition candidate = new PayPalBalanceReportDisposition(UUID.randomUUID(), runId,
                rowNumber, disposition, actor, cleanReason, idempotencyKey, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (store.insertDispositionIfAbsent(candidate).isPresent()) {
            return toResponse(candidate);
        }
        PayPalBalanceReportDispositionResponse existing = jdbc.query("""
                SELECT disposition_id, run_id, row_number, disposition, actor, reason, created_at
                FROM paypal_balance_report_disposition WHERE idempotency_key = ?
                """, DISPOSITION_MAPPER, idempotencyKey).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key conflict"));
        if (existing.runId().equals(runId) && existing.rowNumber() == rowNumber
                && existing.disposition().equals(disposition) && existing.actor().equals(actor)
                && existing.reason().equals(cleanReason)) {
            return existing;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency key was already used for a different disposition");
    }

    private String validateDispositionInput(int rowNumber, String disposition, String actor, String reason,
            String idempotencyKey) {
        if (rowNumber < 1 || rowNumber > MAX_ROW_NUMBER) {
            throw badRequest("rowNumber must be between 1 and 10000");
        }
        if (disposition == null || !List.of("CONFIRMED_EXTERNAL", "DEFERRED", "NEEDS_REVIEW").contains(disposition)) {
            throw badRequest("Unsupported disposition");
        }
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH) {
            throw badRequest("Invalid operator identity");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw badRequest("reason must contain 1 to 500 characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw badRequest("Idempotency-Key must contain 1 to 128 characters");
        }
        return reason.trim();
    }

    private void requireRun(UUID runId) {
        if (store.findRunById(runId).isEmpty()) {
            throw notFound("Report run not found");
        }
    }

    private void requireRow(UUID runId, int rowNumber) {
        if (rowNumber < 1 || rowNumber > MAX_ROW_NUMBER) {
            throw badRequest("rowNumber must be between 1 and 10000");
        }
        try {
            jdbc.queryForObject("SELECT row_number FROM paypal_balance_report_row WHERE run_id = ? AND row_number = ?",
                    Integer.class, runId, rowNumber);
        } catch (EmptyResultDataAccessException e) {
            throw notFound("Report row not found");
        }
    }

    private String rowStatus(UUID runId, int rowNumber) {
        try {
            return jdbc.queryForObject("SELECT status FROM paypal_balance_report_row WHERE run_id = ? AND row_number = ?",
                    String.class, runId, rowNumber);
        } catch (EmptyResultDataAccessException e) {
            throw notFound("Report row not found");
        }
    }

    private static void addDateFilters(StringBuilder sql, List<Object> args, LocalDate from, LocalDate to,
            String timestampColumn) {
        if (from != null) {
            sql.append(" AND ").append(timestampColumn).append(" >= ?");
            args.add(Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND ").append(timestampColumn).append(" < ?");
            args.add(Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
    }

    private static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest("from must be on or before to");
        }
    }

    private static int boundedLimit(int requestedLimit) {
        if (requestedLimit < 1) {
            throw badRequest("limit must be positive");
        }
        return Math.min(requestedLimit, MAX_PAGE_SIZE);
    }

    private static void boundedOffset(int offset) {
        if (offset < 0 || offset > MAX_PAGE_OFFSET) {
            throw badRequest("offset must be between 0 and 10000");
        }
    }

    private static <T> PayPalBalanceReportPage<T> page(List<T> fetched, int limit, int offset) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? fetched.subList(0, limit) : fetched;
        return new PayPalBalanceReportPage<>(items, limit, offset, hasMore);
    }

    private static PayPalBalanceReportDispositionResponse toResponse(PayPalBalanceReportDisposition value) {
        return new PayPalBalanceReportDispositionResponse(value.dispositionId(), value.runId(), value.rowNumber(),
                value.disposition(), value.actor(), value.reason(), value.createdAt());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
