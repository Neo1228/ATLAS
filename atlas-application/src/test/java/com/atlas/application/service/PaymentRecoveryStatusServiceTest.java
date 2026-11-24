package com.atlas.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.atlas.application.dto.response.PaymentHttpResponse.RecoveryStatus.NextAction;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.OrderService;
import com.atlas.payment.model.PaymentState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PaymentRecoveryStatusServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T11:00:00Z");

    private final PaymentRecoveryStatusService service =
            new PaymentRecoveryStatusService(mock(OrderService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void everyPaymentStateMapsToTheDocumentedNextAction() {
        Map<PaymentState, NextAction> expected = Map.of(
                PaymentState.NOT_STARTED, NextAction.CONTACT_SUPPORT,
                PaymentState.AUTHORIZATION_PENDING, NextAction.WAIT,
                PaymentState.AUTHORIZED, NextAction.CONTACT_SUPPORT,
                PaymentState.CAPTURE_PENDING, NextAction.WAIT,
                PaymentState.CAPTURED, NextAction.NO_ACTION,
                PaymentState.DECLINED, NextAction.CONTACT_SUPPORT,
                PaymentState.UNKNOWN, NextAction.WAIT,
                PaymentState.VOID_PENDING, NextAction.WAIT,
                PaymentState.VOIDED, NextAction.NO_ACTION);

        Map<PaymentState, NextAction> actual = Stream.of(PaymentState.values())
                .collect(Collectors.toMap(state -> state,
                        state -> service.nextAction(OrderState.PAYMENT_PENDING, state, null)));

        assertEquals(expected, actual);
    }

    @Test
    void reservedOrderWithoutPaymentCanRetryUntilCheckoutExpires() {
        assertEquals(NextAction.RETRY_PAYMENT, service.nextAction(
                OrderState.RESERVED, PaymentState.NOT_STARTED, Instant.parse("2026-11-16T12:00:00Z")));
        assertEquals(NextAction.RETRY_PAYMENT, service.nextAction(
                OrderState.RESERVED, null, Instant.parse("2026-11-16T12:00:00Z")));
    }

    @Test
    void expiredCheckoutNoLongerOffersRetry() {
        assertEquals(NextAction.CONTACT_SUPPORT, service.nextAction(
                OrderState.RESERVED, PaymentState.NOT_STARTED, Instant.parse("2026-11-16T10:00:00Z")));
        assertEquals(NextAction.CONTACT_SUPPORT, service.nextAction(
                OrderState.RESERVED, PaymentState.NOT_STARTED, NOW));
    }

    @Test
    void missingPaymentOnNonReservedOrderNeedsSupport() {
        assertEquals(NextAction.CONTACT_SUPPORT, service.nextAction(OrderState.PAYMENT_UNKNOWN, null, null));
    }
}
