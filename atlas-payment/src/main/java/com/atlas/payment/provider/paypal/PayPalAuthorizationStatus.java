package com.atlas.payment.provider.paypal;

/**
 * PayPal Payments v2 authorization {@code status}. Orders v2 응답의
 * {@code purchase_units[].payments.authorizations[]}도 같은 값을 쓴다.
 *
 * <p>공식 값: <a href="https://developer.paypal.com/docs/api/payments/v2/">Payments v2</a>의
 * {@code authorization_status}(PayPal OpenAPI {@code payments_payment_v2.json}, {@code checkout_orders_v2.json}).
 */
public enum PayPalAuthorizationStatus {
    CREATED,
    CAPTURED,
    DENIED,
    PARTIALLY_CAPTURED,
    VOIDED,
    PENDING,
    /** 목록에 없는 값이나 누락. 결제를 확정하지 않는 쪽으로 처리한다. */
    UNKNOWN;

    /** PayPal이 보낸 값과 이름이 정확히(대소문자 포함) 같을 때만 그 상수가 된다. */
    static PayPalAuthorizationStatus from(String value) {
        for (PayPalAuthorizationStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
