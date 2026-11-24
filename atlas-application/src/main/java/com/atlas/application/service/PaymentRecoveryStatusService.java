package com.atlas.application.service;

import com.atlas.application.dto.response.PaymentHttpResponse;
import com.atlas.application.dto.response.PaymentHttpResponse.RecoveryStatus.NextAction;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.OrderService;
import com.atlas.payment.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 결제 상태와 주문 상태를 합쳐 구매자가 다음에 무엇을 해야 하는지 판정한다(주문과 결제 모듈을 함께 보므로 application에 둔다). */
@Service
public class PaymentRecoveryStatusService {
    private final OrderService orders;
    private final Clock clock;

    public PaymentRecoveryStatusService(OrderService orders, Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.clock = Objects.requireNonNull(clock);
    }

    public PaymentHttpResponse.RecoveryStatus status(UUID orderId) {
        OrderViewResponse.Order order = orders.find(orderId);
        return new PaymentHttpResponse.RecoveryStatus(orderId, order.paymentState(),
                nextAction(order.state(), order.paymentState(), order.checkoutExpiresAt()));
    }

    NextAction nextAction(OrderState orderState, PaymentState paymentState, Instant checkoutExpiresAt) {
        if (orderState == OrderState.RESERVED
                && (paymentState == null || paymentState == PaymentState.NOT_STARTED)
                && checkoutExpiresAt != null
                && clock.instant().isBefore(checkoutExpiresAt)) {
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
