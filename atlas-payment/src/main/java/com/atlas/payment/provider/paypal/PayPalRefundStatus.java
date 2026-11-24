package com.atlas.payment.provider.paypal;

/**
 * PayPal Payments v2 refund {@code status}.
 *
 * <p>공식 값: <a href="https://developer.paypal.com/docs/api/payments/v2/">Payments v2</a>의
 * {@code refund_status}(PayPal OpenAPI {@code payments_payment_v2.json}).
 * {@link #CREATED}와 {@link #DENIED}는 공식 목록에 없지만 기존 분기가 각각 대기와 실패로 다루던 값이라 그대로 둔다.
 */
public enum PayPalRefundStatus {
    CANCELLED,
    FAILED,
    PENDING,
    COMPLETED,
    CREATED,
    DENIED,
    /** 목록에 없는 값이나 누락. 결제를 확정하지 않는 쪽으로 처리한다. */
    UNKNOWN;

    /** PayPal이 보낸 값과 이름이 정확히(대소문자 포함) 같을 때만 그 상수가 된다. */
    static PayPalRefundStatus from(String value) {
        for (PayPalRefundStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }

    /**
     * 웹훅 resource status용. 웹훅 처리는 도입 때(#207)부터 {@code equalsIgnoreCase}로 비교해 왔으므로
     * 같은 비교로 상수를 찾는다. REST 응답은 {@link #from(String)}처럼 대소문자를 구분한다.
     */
    public static PayPalRefundStatus fromIgnoringCase(String value) {
        for (PayPalRefundStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) return status;
        }
        return UNKNOWN;
    }
}
