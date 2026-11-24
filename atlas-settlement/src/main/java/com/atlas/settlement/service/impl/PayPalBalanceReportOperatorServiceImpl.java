package com.atlas.settlement.service.impl;

import com.atlas.settlement.dto.response.PayPalBalanceReportResponse.OperatorSummary.DailyTrend;
import com.atlas.settlement.dto.response.PayPalBalanceReportResponse;
import com.atlas.settlement.exception.SettlementException.Code;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.model.PayPalBalanceReportDisposition;
import com.atlas.settlement.model.PayPalBalanceReportDispositionType;
import com.atlas.settlement.model.PayPalBalanceReportExceptionRow;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import com.atlas.settlement.model.PayPalBalanceReportRun;
import com.atlas.settlement.service.PayPalBalanceReportOperatorService;
import com.atlas.settlement.service.PayPalBalanceReportStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PayPalBalanceReportOperatorServiceImpl implements PayPalBalanceReportOperatorService {

    private static final int MAX_ROW_NUMBER = 10_000;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Set<PayPalBalanceReportRowStatus> EXCEPTION_STATUSES = EnumSet.of(
            PayPalBalanceReportRowStatus.UNMATCHED, PayPalBalanceReportRowStatus.MISMATCH,
            PayPalBalanceReportRowStatus.DUPLICATE);

    private final PayPalBalanceReportStore store;

    public PayPalBalanceReportOperatorServiceImpl(PayPalBalanceReportStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public PayPalBalanceReportResponse.OperatorSummary summary() {
        List<DailyTrend> trend = store.dailyTrend().stream()
                .map(day -> new DailyTrend(day.date(), day.unmatched(), day.mismatch())).toList();
        long unmatched = trend.stream().mapToLong(DailyTrend::unmatched).sum();
        long mismatch = trend.stream().mapToLong(DailyTrend::mismatch).sum();
        return new PayPalBalanceReportResponse.OperatorSummary(trend, unmatched, mismatch, store.countPendingExceptions());
    }

    @Override
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Run> listRuns(int requestedLimit, int offset) {
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        return page(store.listRuns(limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorServiceImpl::runResponse).toList(), limit, offset);
    }

    @Override
    public PayPalBalanceReportResponse.Run search(String reportSha256) {
        if (reportSha256 == null || !reportSha256.matches("[0-9a-f]{64}")) {
            throw badRequest("reportSha256 must be a lowercase SHA-256 digest");
        }
        return store.findRunBySha256(reportSha256)
                .map(PayPalBalanceReportOperatorServiceImpl::runResponse)
                .orElseThrow(() -> notFound("Report run not found"));
    }

    @Override
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> listExceptions(PayPalBalanceReportRowStatus status, LocalDate from,
            LocalDate to, String recordId, int requestedLimit, int offset) {
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        if (status != null && !EXCEPTION_STATUSES.contains(status)) {
            throw badRequest("status must be UNMATCHED, MISMATCH, or DUPLICATE");
        }
        validateDateRange(from, to);
        return page(store.listExceptions(status, from, to, recordId, limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorServiceImpl::rowResponse).toList(), limit, offset);
    }

    @Override
    public PayPalBalanceReportResponse.Page<PayPalBalanceReportResponse.Row> listRunRows(UUID runId, PayPalBalanceReportRowStatus status,
            String recordId, int requestedLimit, int offset) {
        requireRun(runId);
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        return page(store.listRunRows(runId, status, recordId, limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorServiceImpl::rowResponse).toList(), limit, offset);
    }

    @Override
    public List<PayPalBalanceReportResponse.Disposition> dispositionHistory(UUID runId, int rowNumber) {
        requireRun(runId);
        requireRow(runId, rowNumber);
        return store.findDispositionHistory(runId, rowNumber, MAX_PAGE_SIZE).stream()
                .map(PayPalBalanceReportOperatorServiceImpl::toResponse).toList();
    }

    @Override
    public PayPalBalanceReportResponse.Disposition recordDisposition(UUID runId, int rowNumber, PayPalBalanceReportDispositionType disposition,
            String actor, String reason, String idempotencyKey) {
        requireRun(runId);
        String cleanReason = validateDispositionInput(rowNumber, disposition, actor, reason, idempotencyKey);
        PayPalBalanceReportRowStatus rowStatus = rowStatus(runId, rowNumber);
        if (rowStatus == PayPalBalanceReportRowStatus.MATCHED) {
            throw new SettlementException(Code.CONFLICT, "Matched rows cannot be dispositioned");
        }
        if (!EXCEPTION_STATUSES.contains(rowStatus)) {
            throw new SettlementException(Code.CONFLICT, "Only exception rows can be dispositioned");
        }

        PayPalBalanceReportDisposition candidate = new PayPalBalanceReportDisposition(UUID.randomUUID(), runId,
                rowNumber, disposition, actor, cleanReason, idempotencyKey, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (store.insertDispositionIfAbsent(candidate).isPresent()) {
            return toResponse(candidate);
        }
        PayPalBalanceReportResponse.Disposition existing = store.findDispositionByIdempotencyKey(idempotencyKey)
                .map(PayPalBalanceReportOperatorServiceImpl::toResponse)
                .orElseThrow(() -> new SettlementException(Code.CONFLICT, "Idempotency key conflict"));
        if (existing.runId().equals(runId) && existing.rowNumber() == rowNumber
                && existing.disposition() == disposition && existing.actor().equals(actor)
                && existing.reason().equals(cleanReason)) {
            return existing;
        }
        throw new SettlementException(Code.CONFLICT,
                "Idempotency key was already used for a different disposition");
    }

    private String validateDispositionInput(int rowNumber, PayPalBalanceReportDispositionType disposition, String actor, String reason,
            String idempotencyKey) {
        if (rowNumber < 1 || rowNumber > MAX_ROW_NUMBER) {
            throw badRequest("rowNumber must be between 1 and 10000");
        }
        if (disposition == null) {
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
        if (store.findRowStatus(runId, rowNumber).isEmpty()) {
            throw notFound("Report row not found");
        }
    }

    private PayPalBalanceReportRowStatus rowStatus(UUID runId, int rowNumber) {
        return store.findRowStatus(runId, rowNumber).orElseThrow(() -> notFound("Report row not found"));
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

    private static <T> PayPalBalanceReportResponse.Page<T> page(List<T> fetched, int limit, int offset) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? fetched.subList(0, limit) : fetched;
        return new PayPalBalanceReportResponse.Page<>(items, limit, offset, hasMore);
    }

    private static PayPalBalanceReportResponse.Disposition toResponse(PayPalBalanceReportDisposition value) {
        return new PayPalBalanceReportResponse.Disposition(value.dispositionId(), value.runId(), value.rowNumber(),
                value.disposition(), value.actor(), value.reason(), value.createdAt());
    }

    private static PayPalBalanceReportResponse.Run runResponse(PayPalBalanceReportRun run) {
        return new PayPalBalanceReportResponse.Run(run.runId(), run.reportSha256(), run.uploadedAt(),
                run.reportFormat(), run.rowCount(), run.matchedCount(), run.unmatchedCount(), run.mismatchCount(),
                run.duplicateCount());
    }

    private static PayPalBalanceReportResponse.Row rowResponse(PayPalBalanceReportExceptionRow row) {
        return new PayPalBalanceReportResponse.Row(row.runId(), row.rowNumber(), row.recordId(),
                row.relatedRecordId(), row.recordType(), row.status(), row.reasonCode());
    }

    private static SettlementException badRequest(String message) {
        return new SettlementException(Code.INVALID_REQUEST, message);
    }

    private static SettlementException notFound(String message) {
        return new SettlementException(Code.NOT_FOUND, message);
    }
}
