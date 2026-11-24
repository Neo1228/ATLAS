package com.atlas.payment.provider.paypal;

/**
 * PayPal Orders v2 order {@code status}.
 *
 * <p>공식 값: <a href="https://developer.paypal.com/docs/api/orders/v2/">Orders v2</a>의
 * {@code order_status}(PayPal OpenAPI {@code checkout_orders_v2.json}).
 */
public enum PayPalOrderStatus {
    CREATED,
    SAVED,
    APPROVED,
    VOIDED,
    COMPLETED,
    PAYER_ACTION_REQUIRED,
    /** 목록에 없는 값이나 누락. 결제를 확정하지 않는 쪽으로 처리한다. */
    UNKNOWN;

    /** PayPal이 보낸 값과 이름이 정확히(대소문자 포함) 같을 때만 그 상수가 된다. */
    static PayPalOrderStatus from(String value) {
        for (PayPalOrderStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
