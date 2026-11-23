package com.atlas.application.service;

import com.atlas.application.dto.response.PayPalBalanceReportDispositionResponse;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary;
import com.atlas.application.dto.response.PayPalBalanceReportOperatorSummary.DailyTrend;
import com.atlas.application.dto.response.PayPalBalanceReportPage;
import com.atlas.application.dto.response.PayPalBalanceReportRowResponse;
import com.atlas.application.dto.response.PayPalBalanceReportRunResponse;
import com.atlas.application.exception.ApplicationException.Code;
import com.atlas.application.exception.ApplicationException;
import com.atlas.settlement.service.PayPalBalanceReportDisposition;
import com.atlas.settlement.service.PayPalBalanceReportExceptionRow;
import com.atlas.settlement.service.PayPalBalanceReportRun;
import com.atlas.settlement.service.PayPalBalanceReportStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

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

    private final PayPalBalanceReportStore store;

    public PayPalBalanceReportOperatorService(PayPalBalanceReportStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public PayPalBalanceReportOperatorSummary summary() {
        List<DailyTrend> trend = store.dailyTrend().stream()
                .map(day -> new DailyTrend(day.date(), day.unmatched(), day.mismatch())).toList();
        long unmatched = trend.stream().mapToLong(DailyTrend::unmatched).sum();
        long mismatch = trend.stream().mapToLong(DailyTrend::mismatch).sum();
        return new PayPalBalanceReportOperatorSummary(trend, unmatched, mismatch, store.countPendingExceptions());
    }

    public PayPalBalanceReportPage<PayPalBalanceReportRunResponse> listRuns(int requestedLimit, int offset) {
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        return page(store.listRuns(limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorService::runResponse).toList(), limit, offset);
    }

    public PayPalBalanceReportRunResponse search(String reportSha256) {
        if (reportSha256 == null || !reportSha256.matches("[0-9a-f]{64}")) {
            throw badRequest("reportSha256 must be a lowercase SHA-256 digest");
        }
        return store.findRunBySha256(reportSha256)
                .map(PayPalBalanceReportOperatorService::runResponse)
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
        return page(store.listExceptions(status, from, to, recordId, limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorService::rowResponse).toList(), limit, offset);
    }

    public PayPalBalanceReportPage<PayPalBalanceReportRowResponse> listRunRows(UUID runId, String status,
            String recordId, int requestedLimit, int offset) {
        requireRun(runId);
        int limit = boundedLimit(requestedLimit);
        boundedOffset(offset);
        if (status != null && !ROW_STATUSES.contains(status)) {
            throw badRequest("Unsupported row status");
        }
        return page(store.listRunRows(runId, status, recordId, limit + 1, offset).stream()
                .map(PayPalBalanceReportOperatorService::rowResponse).toList(), limit, offset);
    }

    public List<PayPalBalanceReportDispositionResponse> dispositionHistory(UUID runId, int rowNumber) {
        requireRun(runId);
        requireRow(runId, rowNumber);
        return store.findDispositionHistory(runId, rowNumber, MAX_PAGE_SIZE).stream()
                .map(PayPalBalanceReportOperatorService::toResponse).toList();
    }

    public PayPalBalanceReportDispositionResponse recordDisposition(UUID runId, int rowNumber, String disposition,
            String actor, String reason, String idempotencyKey) {
        requireRun(runId);
        String cleanReason = validateDispositionInput(rowNumber, disposition, actor, reason, idempotencyKey);
        String rowStatus = rowStatus(runId, rowNumber);
        if ("MATCHED".equals(rowStatus)) {
            throw new ApplicationException(Code.CONFLICT, "Matched rows cannot be dispositioned");
        }
        if (!EXCEPTION_STATUSES.contains(rowStatus)) {
            throw new ApplicationException(Code.CONFLICT, "Only exception rows can be dispositioned");
        }

        PayPalBalanceReportDisposition candidate = new PayPalBalanceReportDisposition(UUID.randomUUID(), runId,
                rowNumber, disposition, actor, cleanReason, idempotencyKey, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (store.insertDispositionIfAbsent(candidate).isPresent()) {
            return toResponse(candidate);
        }
        PayPalBalanceReportDispositionResponse existing = store.findDispositionByIdempotencyKey(idempotencyKey)
                .map(PayPalBalanceReportOperatorService::toResponse)
                .orElseThrow(() -> new ApplicationException(Code.CONFLICT, "Idempotency key conflict"));
        if (existing.runId().equals(runId) && existing.rowNumber() == rowNumber
                && existing.disposition().equals(disposition) && existing.actor().equals(actor)
                && existing.reason().equals(cleanReason)) {
            return existing;
        }
        throw new ApplicationException(Code.CONFLICT,
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
        if (store.findRowStatus(runId, rowNumber).isEmpty()) {
            throw notFound("Report row not found");
        }
    }

    private String rowStatus(UUID runId, int rowNumber) {
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

    private static <T> PayPalBalanceReportPage<T> page(List<T> fetched, int limit, int offset) {
        boolean hasMore = fetched.size() > limit;
        List<T> items = hasMore ? fetched.subList(0, limit) : fetched;
        return new PayPalBalanceReportPage<>(items, limit, offset, hasMore);
    }

    private static PayPalBalanceReportDispositionResponse toResponse(PayPalBalanceReportDisposition value) {
        return new PayPalBalanceReportDispositionResponse(value.dispositionId(), value.runId(), value.rowNumber(),
                value.disposition(), value.actor(), value.reason(), value.createdAt());
    }

    private static PayPalBalanceReportRunResponse runResponse(PayPalBalanceReportRun run) {
        return new PayPalBalanceReportRunResponse(run.runId(), run.reportSha256(), run.uploadedAt(),
                run.reportFormat(), run.rowCount(), run.matchedCount(), run.unmatchedCount(), run.mismatchCount(),
                run.duplicateCount());
    }

    private static PayPalBalanceReportRowResponse rowResponse(PayPalBalanceReportExceptionRow row) {
        return new PayPalBalanceReportRowResponse(row.runId(), row.rowNumber(), row.recordId(),
                row.relatedRecordId(), row.recordType(), row.status(), row.reasonCode());
    }

    private static ApplicationException badRequest(String message) {
        return new ApplicationException(Code.INVALID_REQUEST, message);
    }

    private static ApplicationException notFound(String message) {
        return new ApplicationException(Code.NOT_FOUND, message);
    }
}
