package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PayPalWebhookRetryPersistenceTest {
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final PayPalWebhookRetryRepository retries = mock(PayPalWebhookRetryRepository.class);
    private final PaymentWebhookEvent event = new PaymentWebhookEvent(
            "event-retry", "PAYMENT.CAPTURE.COMPLETED", "capture-1", "COMPLETED",
            "provider-order-1", null, null, "USD");

    private PayPalWebhookService service() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(payments.verifyPayPalWebhook(any(), eq("body"))).thenReturn(event);
        when(payments.withPayPalWebhookOwnerLock(eq(event), any())).thenAnswer(invocation ->
                invocation.<Supplier<PaymentWebhookResult>>getArgument(1).get());
        when(payments.processVerifiedPayPalWebhook(event))
                .thenReturn(new PaymentWebhookResult(false, false, null, null, null, false, true));
        return new PayPalWebhookService(transactions, payments, mock(OrderService.class),
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class), retries);
    }

    @Test
    void persistsRetryableVerifiedEventBeforeReturningSuccess() {
        service().handle(signature(), "body");
        verify(retries).enqueue(event);
    }

    @Test
    void badSignatureIsNeverPersistedForRetry() {
        PayPalWebhookService webhook = service();
        when(payments.verifyPayPalWebhook(any(), eq("body")))
                .thenThrow(new PaymentException(PaymentException.Code.INVALID_REQUEST, "bad signature"));

        assertThatThrownBy(() -> webhook.handle(signature(), "body"))
                .isInstanceOf(PaymentException.class);
        verify(retries, never()).enqueue(any());
    }

    @Test
    void persistenceFailurePropagatesSoWebhookIsNotAcknowledged() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(retries).enqueue(event);
        assertThatThrownBy(() -> service().handle(signature(), "body"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaymentWebhookSignature signature() {
        return new PaymentWebhookSignature("alg", "cert", "transmission", "signature", "time");
    }
}
