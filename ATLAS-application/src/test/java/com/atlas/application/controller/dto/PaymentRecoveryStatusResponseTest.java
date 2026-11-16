package com.atlas.application.controller.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.order.persistence.state.OrderState;
import com.atlas.payment.persistence.state.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PaymentRecoveryStatusResponseTest {
    @Test
    void mapsEveryPaymentStateConservatively() {
        Map<PaymentState, PaymentRecoveryStatusResponse.NextAction> expected = Map.of(
                PaymentState.NOT_STARTED, PaymentRecoveryStatusResponse.NextAction.CONTACT_SUPPORT,
                PaymentState.AUTHORIZATION_PENDING, PaymentRecoveryStatusResponse.NextAction.WAIT,
                PaymentState.AUTHORIZED, PaymentRecoveryStatusResponse.NextAction.CONTACT_SUPPORT,
                PaymentState.CAPTURE_PENDING, PaymentRecoveryStatusResponse.NextAction.WAIT,
                PaymentState.CAPTURED, PaymentRecoveryStatusResponse.NextAction.NO_ACTION,
                PaymentState.DECLINED, PaymentRecoveryStatusResponse.NextAction.CONTACT_SUPPORT,
                PaymentState.UNKNOWN, PaymentRecoveryStatusResponse.NextAction.WAIT,
                PaymentState.VOID_PENDING, PaymentRecoveryStatusResponse.NextAction.WAIT,
                PaymentState.VOIDED, PaymentRecoveryStatusResponse.NextAction.NO_ACTION);

        Map<PaymentState, PaymentRecoveryStatusResponse.NextAction> actual = Stream.of(PaymentState.values())
                .collect(Collectors.toMap(state -> state,
                        state -> PaymentRecoveryStatusResponse.from(
                                UUID.randomUUID(), OrderState.PAYMENT_PENDING, state, null, null).nextAction()));

        assertEquals(expected, actual);
    }

    @Test
    void onlyReservedOrderWithNoPaymentCanBeRetried() {
        PaymentRecoveryStatusResponse response = PaymentRecoveryStatusResponse.from(
                UUID.randomUUID(), OrderState.RESERVED, PaymentState.NOT_STARTED,
                Instant.parse("2026-11-16T12:00:00Z"), Instant.parse("2026-11-16T11:00:00Z"));

        assertEquals(PaymentRecoveryStatusResponse.NextAction.RETRY_PAYMENT, response.nextAction());
    }

    @Test
    void expiredReservedOrderDoesNotAdvertiseRetry() {
        PaymentRecoveryStatusResponse response = PaymentRecoveryStatusResponse.from(
                UUID.randomUUID(), OrderState.RESERVED, PaymentState.NOT_STARTED,
                Instant.parse("2026-11-16T10:00:00Z"), Instant.parse("2026-11-16T11:00:00Z"));

        assertEquals(PaymentRecoveryStatusResponse.NextAction.CONTACT_SUPPORT, response.nextAction());
    }

    @Test
    void missingPaymentStateOnNonReservedOrderRequiresSupport() {
        PaymentRecoveryStatusResponse response = PaymentRecoveryStatusResponse.from(
                UUID.randomUUID(), OrderState.PAYMENT_UNKNOWN, null, null, null);

        assertEquals(PaymentRecoveryStatusResponse.NextAction.CONTACT_SUPPORT, response.nextAction());
    }

    @Test
    void responseContainsOnlyBuyerSafeFields() {
        assertEquals(List.of("orderId", "paymentState", "nextAction"),
                Stream.of(PaymentRecoveryStatusResponse.class.getRecordComponents())
                        .map(component -> component.getName()).toList());
    }
}
