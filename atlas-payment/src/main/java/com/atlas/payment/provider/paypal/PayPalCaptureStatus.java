package com.atlas.payment.provider.paypal;

/**
 * PayPal Payments v2 capture {@code status}. Orders v2 응답의
 * {@code purchase_units[].payments.captures[]}도 같은 값을 쓴다.
 *
 * <p>공식 값: <a href="https://developer.paypal.com/docs/api/payments/v2/">Payments v2</a>의
 * {@code capture_status}(PayPal OpenAPI {@code payments_payment_v2.json}, {@code checkout_orders_v2.json}).
 * {@link #DENIED}는 공식 목록에 없지만 기존 분기가 거절로 다루던 값이라 그대로 둔다.
 */
public enum PayPalCaptureStatus {
    COMPLETED,
    DECLINED,
    PARTIALLY_REFUNDED,
    PENDING,
    REFUNDED,
    FAILED,
    DENIED,
    /** 목록에 없는 값이나 누락. 결제를 확정하지 않는 쪽으로 처리한다. */
    UNKNOWN;

    /** PayPal이 보낸 값과 이름이 정확히(대소문자 포함) 같을 때만 그 상수가 된다. */
    static PayPalCaptureStatus from(String value) {
        for (PayPalCaptureStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
