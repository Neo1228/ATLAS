package com.atlas.settlement.repository;

import com.atlas.settlement.service.PayPalBalanceReportDisposition;
import com.atlas.settlement.service.PayPalBalanceReportRow;
import com.atlas.settlement.service.PayPalBalanceReportRun;
import com.atlas.settlement.service.PayPalBalanceReportRunSummary;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PayPal 보고서 사실과 disposition 감사 이력만 저장합니다. */
@Repository
public class PayPalBalanceReportRunRepository {
    private static final int MAX_PAGE_SIZE = 500;

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

    public PayPalBalanceReportRunRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** run과 행을 원자적으로 추가하며, 같은 SHA-256은 기존 run을 재사용합니다. */
    @Transactional
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

    public Optional<PayPalBalanceReportRun> findRunById(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM paypal_balance_report_run WHERE run_id = ?",
                RUN_MAPPER, runId).stream().findFirst();
    }

    public Optional<PayPalBalanceReportRun> findRunBySha256(String reportSha256) {
        Objects.requireNonNull(reportSha256, "reportSha256");
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM paypal_balance_report_run WHERE report_sha256 = ?",
                RUN_MAPPER, reportSha256).stream().findFirst();
    }

    /** 최근 run부터 정렬해 제한된 개수만 조회합니다. */
    public List<PayPalBalanceReportRunSummary> listRunSummaries(int limit) {
        requireLimit(limit);
        return jdbc.query("""
                SELECT run_id, report_sha256, uploaded_at, report_format, row_count,
                       matched_count, unmatched_count, mismatch_count, duplicate_count
                FROM paypal_balance_report_run
                ORDER BY uploaded_at DESC, run_id
                LIMIT ?
                """, (rs, rowNum) -> new PayPalBalanceReportRunSummary(rs.getObject("run_id", UUID.class),
                rs.getString("report_sha256"), rs.getTimestamp("uploaded_at").toInstant(), rs.getString("report_format"),
                rs.getInt("row_count"), rs.getInt("matched_count"), rs.getInt("unmatched_count"),
                rs.getInt("mismatch_count"), rs.getInt("duplicate_count")), limit);
    }

    /** 보고서 행 순서로 최대 500건을 조회합니다. */
    public List<PayPalBalanceReportRow> listRows(UUID runId, int limit) {
        Objects.requireNonNull(runId, "runId");
        requireLimit(limit);
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM paypal_balance_report_row "
                + "WHERE run_id = ? ORDER BY row_number LIMIT ?", ROW_MAPPER, runId, limit);
    }
    /** 파서가 허용하는 최대 10,000개 행을 순서대로 조회합니다. */
    public List<PayPalBalanceReportRow> listAllRows(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM paypal_balance_report_row "
                + "WHERE run_id = ? ORDER BY row_number LIMIT 10000", ROW_MAPPER, runId);
    }


    /** idempotency key가 없을 때 disposition을 추가합니다. */
    @Transactional
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

    public Optional<PayPalBalanceReportDisposition> findDispositionByIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return jdbc.query("SELECT " + DISPOSITION_COLUMNS
                + " FROM paypal_balance_report_disposition WHERE idempotency_key = ?",
                DISPOSITION_MAPPER, idempotencyKey).stream().findFirst();
    }

    /** run과 행별 disposition 이력을 오래된 순서로 조회합니다. */
    public List<PayPalBalanceReportDisposition> findDispositionHistory(UUID runId, int rowNumber) {
        Objects.requireNonNull(runId, "runId");
        if (rowNumber < 1 || rowNumber > 10_000) {
            throw new IllegalArgumentException("rowNumber must be between 1 and 10000");
        }
        return jdbc.query("SELECT " + DISPOSITION_COLUMNS + " FROM paypal_balance_report_disposition "
                + "WHERE run_id = ? AND row_number = ? ORDER BY created_at, disposition_id",
                DISPOSITION_MAPPER, runId, rowNumber);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
    }
}
