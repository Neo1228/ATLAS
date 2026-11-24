package com.atlas.payment.provider.paypal;

/**
 * PayPal webhook 서명 검증 응답의 {@code verification_status}.
 *
 * <p>공식 값: <a href="https://developer.paypal.com/docs/api/webhooks/v1/">Webhooks Management v1</a>의
 * {@code verify_webhook_signature_response}(PayPal OpenAPI {@code notifications_webhooks_v1.json}).
 */
public enum PayPalWebhookVerificationStatus {
    SUCCESS,
    FAILURE,
    /** 목록에 없는 값이나 누락. 결제를 확정하지 않는 쪽으로 처리한다. */
    UNKNOWN;

    /** PayPal이 보낸 값과 이름이 정확히(대소문자 포함) 같을 때만 그 상수가 된다. */
    static PayPalWebhookVerificationStatus from(String value) {
        for (PayPalWebhookVerificationStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
