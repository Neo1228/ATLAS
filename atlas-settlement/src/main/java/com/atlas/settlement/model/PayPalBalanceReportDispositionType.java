package com.atlas.settlement.model;

/**
 * 예외 행에 대한 운영자 처리 결정.
 *
 * <p>값 집합은 {@code paypal_balance_report_disposition_disposition_check} 제약과 같다.
 */
public enum PayPalBalanceReportDispositionType {
    CONFIRMED_EXTERNAL,
    DEFERRED,
    NEEDS_REVIEW
}
