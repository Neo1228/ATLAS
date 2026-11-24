package com.atlas.settlement.service;

import com.atlas.settlement.model.ParsedPayPalReport;

/** PayPal 잔액 보고서 파일을 행 단위 모델로 읽는다. 파일 형식(CSV 등)은 구현이 안다. */
public interface PayPalBalanceReportParser {
    /** 한 번에 받아들이는 보고서의 최대 크기(바이트). */
    int MAX_REPORT_BYTES = 10 * 1024 * 1024;

    ParsedPayPalReport parse(byte[] content);
}
