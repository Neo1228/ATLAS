package com.atlas.application.dto.response;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.order.model.OrderState;
import com.atlas.payment.model.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PaymentHttpResponseTest {
    @Test
    void mapsEveryPaymentStateConservatively() {
        Map<PaymentState, PaymentHttpResponse.RecoveryStatus.NextAction> expected = Map.of(
                PaymentState.NOT_STARTED, PaymentHttpResponse.RecoveryStatus.NextAction.CONTACT_SUPPORT,
                PaymentState.AUTHORIZATION_PENDING, PaymentHttpResponse.RecoveryStatus.NextAction.WAIT,
                PaymentState.AUTHORIZED, PaymentHttpResponse.RecoveryStatus.NextAction.CONTACT_SUPPORT,
                PaymentState.CAPTURE_PENDING, PaymentHttpResponse.RecoveryStatus.NextAction.WAIT,
                PaymentState.CAPTURED, PaymentHttpResponse.RecoveryStatus.NextAction.NO_ACTION,
                PaymentState.DECLINED, PaymentHttpResponse.RecoveryStatus.NextAction.CONTACT_SUPPORT,
                PaymentState.UNKNOWN, PaymentHttpResponse.RecoveryStatus.NextAction.WAIT,
                PaymentState.VOID_PENDING, PaymentHttpResponse.RecoveryStatus.NextAction.WAIT,
                PaymentState.VOIDED, PaymentHttpResponse.RecoveryStatus.NextAction.NO_ACTION);

        Map<PaymentState, PaymentHttpResponse.RecoveryStatus.NextAction> actual = Stream.of(PaymentState.values())
                .collect(Collectors.toMap(state -> state,
                        state -> PaymentHttpResponse.RecoveryStatus.from(
                                UUID.randomUUID(), OrderState.PAYMENT_PENDING, state, null, null).nextAction()));

        assertEquals(expected, actual);
    }

    @Test
    void onlyReservedOrderWithNoPaymentCanBeRetried() {
        PaymentHttpResponse.RecoveryStatus response = PaymentHttpResponse.RecoveryStatus.from(
                UUID.randomUUID(), OrderState.RESERVED, PaymentState.NOT_STARTED,
                Instant.parse("2026-11-16T12:00:00Z"), Instant.parse("2026-11-16T11:00:00Z"));

        assertEquals(PaymentHttpResponse.RecoveryStatus.NextAction.RETRY_PAYMENT, response.nextAction());
    }

    @Test
    void expiredReservedOrderDoesNotAdvertiseRetry() {
        PaymentHttpResponse.RecoveryStatus response = PaymentHttpResponse.RecoveryStatus.from(
                UUID.randomUUID(), OrderState.RESERVED, PaymentState.NOT_STARTED,
                Instant.parse("2026-11-16T10:00:00Z"), Instant.parse("2026-11-16T11:00:00Z"));

        assertEquals(PaymentHttpResponse.RecoveryStatus.NextAction.CONTACT_SUPPORT, response.nextAction());
    }

    @Test
    void missingPaymentStateOnNonReservedOrderRequiresSupport() {
        PaymentHttpResponse.RecoveryStatus response = PaymentHttpResponse.RecoveryStatus.from(
                UUID.randomUUID(), OrderState.PAYMENT_UNKNOWN, null, null, null);

        assertEquals(PaymentHttpResponse.RecoveryStatus.NextAction.CONTACT_SUPPORT, response.nextAction());
    }

    @Test
    void responseContainsOnlyBuyerSafeFields() {
        assertEquals(List.of("orderId", "paymentState", "nextAction"),
                Stream.of(PaymentHttpResponse.RecoveryStatus.class.getRecordComponents())
                        .map(component -> component.getName()).toList());
    }
}
