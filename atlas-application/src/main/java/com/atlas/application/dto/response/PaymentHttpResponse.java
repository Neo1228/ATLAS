package com.atlas.application.dto.response;

import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.payment.model.PaymentState;
import java.util.List;
import java.util.UUID;

public final class PaymentHttpResponse {
    private PaymentHttpResponse() {
    }

    /** 구매자에게 결제 상태와 안전한 다음 동작만 반환합니다. */
    public record RecoveryStatus(UUID orderId, PaymentState paymentState, NextAction nextAction) {
        public enum NextAction {
            RETRY_PAYMENT,
            WAIT,
            NO_ACTION,
            CONTACT_SUPPORT
        }
    }

    public record RecoveryReviewPage(
            List<PaymentRecoveryResponse.ReviewItem> items,
            int limit,
            int offset,
            long total) {
    }
}
