package com.atlas.payment.dto.response;

import java.time.Instant;

/**
 * provider 호출 결과. {@code authorizationExpiresAt} 은 provider 가 authorization 의 만료 시각을
 * 알려 준 경우에만 값이 있고, 결제 상태를 정하는 데는 쓰지 않는다.
 */
public record PaymentProviderResponse(Outcome outcome, String orderId, String authorizationId,
        String captureId, String approvalUrl, Instant authorizationExpiresAt) {
    public PaymentProviderResponse {
        if (outcome == null) throw new IllegalArgumentException("Provider outcome is required");
    }

    public PaymentProviderResponse(Outcome outcome, String orderId, String authorizationId,
            String captureId, String approvalUrl) {
        this(outcome, orderId, authorizationId, captureId, approvalUrl, null);
    }

    public enum Outcome { AUTHORIZED, CAPTURED, DECLINED, PENDING, UNKNOWN, VOIDED }
}
