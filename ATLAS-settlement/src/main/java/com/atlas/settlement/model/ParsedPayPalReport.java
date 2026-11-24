package com.atlas.settlement.model;

import java.util.List;

/** PayPal 잔액 보고서 CSV를 읽은 결과. 보고서 이름과 정규화한 행 목록. */
public record ParsedPayPalReport(String reportName, List<ParsedPayPalReportRow> rows) {
    public ParsedPayPalReport {
        rows = List.copyOf(rows);
    }
}
