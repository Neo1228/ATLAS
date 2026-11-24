package com.atlas.settlement.model;

/**
 * PayPal 잔액 보고서 행의 대사 결과.
 *
 * <p>값 집합은 {@code paypal_balance_report_row_status_check} 제약과 같다. MATCHED 가 아닌 세 값이
 * 운영자 처리 대상인 예외 행이다.
 */
public enum PayPalBalanceReportRowStatus {
    MATCHED,
    UNMATCHED,
    DUPLICATE,
    MISMATCH
}
