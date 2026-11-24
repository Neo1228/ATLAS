package com.atlas.application.dto.response;

import com.atlas.order.model.OrderState;
import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.payment.model.PaymentState;
import java.time.Instant;
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

        public static RecoveryStatus from(
                UUID orderId, OrderState orderState, PaymentState paymentState,
                Instant checkoutExpiresAt, Instant now) {
            return new RecoveryStatus(
                    orderId, paymentState, nextActionFor(orderState, paymentState, checkoutExpiresAt, now));
        }

        private static NextAction nextActionFor(
                OrderState orderState, PaymentState paymentState, Instant checkoutExpiresAt, Instant now) {
            if (orderState == OrderState.RESERVED
                    && (paymentState == null || paymentState == PaymentState.NOT_STARTED)
                    && checkoutExpiresAt != null
                    && now.isBefore(checkoutExpiresAt)) {
                return NextAction.RETRY_PAYMENT;
            }
            if (paymentState == null) {
                return NextAction.CONTACT_SUPPORT;
            }
            return switch (paymentState) {
                case AUTHORIZATION_PENDING, CAPTURE_PENDING, UNKNOWN, VOID_PENDING -> NextAction.WAIT;
                case CAPTURED, VOIDED -> NextAction.NO_ACTION;
                case NOT_STARTED, AUTHORIZED, DECLINED -> NextAction.CONTACT_SUPPORT;
            };
        }
    }

    public record RecoveryReviewPage(
            List<PaymentRecoveryResponse.ReviewItem> items,
            int limit,
            int offset,
            long total) {
    }
}
