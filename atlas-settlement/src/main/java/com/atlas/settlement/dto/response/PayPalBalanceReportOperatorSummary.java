package com.atlas.settlement.dto.response;

import java.time.LocalDate;
import java.util.List;

/** 최근 30일 예외 추이와 미완료 운영 작업 요약입니다. */
public record PayPalBalanceReportOperatorSummary(List<DailyTrend> trend, long unmatchedLast30Days,
        long mismatchLast30Days, long pendingDispositions) {
    public PayPalBalanceReportOperatorSummary {
        trend = List.copyOf(trend);
    }

    public record DailyTrend(LocalDate date, long unmatched, long mismatch) {
    }
}
