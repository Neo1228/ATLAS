package com.atlas.settlement.dto.response;

import com.atlas.settlement.model.PayPalBalanceReportDispositionType;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PayPalBalanceReportResponse {
    private PayPalBalanceReportResponse() {
    }

    public record Disposition(UUID dispositionId, UUID runId, int rowNumber,
            PayPalBalanceReportDispositionType disposition, String actor, String reason, Instant createdAt) {
    }

    /** 최근 30일 예외 추이와 미완료 운영 작업 요약입니다. */
    public record OperatorSummary(List<DailyTrend> trend, long unmatchedLast30Days,
            long mismatchLast30Days, long pendingDispositions) {
        public OperatorSummary {
            trend = List.copyOf(trend);
        }

        public record DailyTrend(LocalDate date, long unmatched, long mismatch) {
        }
    }

    /** 페이지 크기가 제한된 조회 결과입니다. */
    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /** 금액과 원본 식별 정보를 제외한 대사 행 조회 DTO입니다. */
    public record Row(UUID runId, int rowNumber, String recordId,
            String relatedRecordId, String recordType, PayPalBalanceReportRowStatus status, String reasonCode) {
    }

    public record Run(UUID runId, String reportSha256, Instant uploadedAt,
            String reportFormat, int rowCount, int matchedCount, int unmatchedCount, int mismatchCount,
            int duplicateCount) {
    }
}
