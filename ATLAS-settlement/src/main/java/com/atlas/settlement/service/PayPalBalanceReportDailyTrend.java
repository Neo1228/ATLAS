package com.atlas.settlement.service;

import java.time.LocalDate;

/** 하루 동안 업로드된 보고서의 UNMATCHED, MISMATCH 건수 합계. 업로드가 없는 날은 0이다. */
public record PayPalBalanceReportDailyTrend(LocalDate date, long unmatched, long mismatch) {
}
