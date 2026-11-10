package com.atlas.payment.dto.response;

import com.atlas.payment.persistence.state.PaymentState;
import java.util.UUID;

/** 주문 또는 구매 checkout의 결제 시도 결과를 나타낸다. */
public record PaymentResponse(UUID orderId, UUID checkoutId, UUID attemptId, PaymentState state,
        boolean authorizationActive, String providerOrderId, String providerCaptureId, String approvalUrl) {
    public PaymentResponse(UUID orderId, UUID checkoutId, UUID attemptId, PaymentState state,
            boolean authorizationActive) {
        this(orderId, checkoutId, attemptId, state, authorizationActive, null, null, null);
    }

    /** 주문에 속한 결제 시도를 생성하는 기존 호출부를 위한 생성자. */
    public PaymentResponse(UUID orderId, UUID attemptId, PaymentState state, boolean authorizationActive) {
        this(orderId, null, attemptId, state, authorizationActive, null, null, null);
    }
}
