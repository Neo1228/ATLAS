package com.atlas.payment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.persistence.entity.PaymentAttempt;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.repository.PaymentAttemptRepository;
import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.repository.PaymentWebhookEventRepository;
import com.atlas.payment.service.FxConversionService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentServiceImplWebhookLockTest {
    @Test
    void captureWebhookOwnerLockSerializesBeforeSynchronousReconciliationTransaction() throws Exception {
        UUID orderId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt(orderId, UUID.randomUUID(), PaymentState.CAPTURED,
                PaymentPhase.CAPTURE, false);
        attempt.setProviderOrderId("provider-order");
        attempt.setProviderCaptureId("provider-capture");
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findByProviderOrderId("provider-order")).thenReturn(Optional.of(attempt));
        when(attempts.findByOrderId(orderId)).thenReturn(Optional.of(attempt));

        CountDownLatch transactionEntered = new CountDownLatch(1);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            transactionEntered.countDown();
            return new SimpleTransactionStatus();
        });
        PaymentServiceImpl payments = new PaymentServiceImpl(attempts, mock(PaymentRefundRepository.class),
                mock(PaymentWebhookEventRepository.class), transactionManager, mock(PaymentProvider.class),
                mock(FxConversionService.class), "https://example.test");
        PaymentWebhookEvent event = new PaymentWebhookEvent("event-1", "PAYMENT.CAPTURE.COMPLETED",
                "provider-capture", "COMPLETED", "provider-order", null, null, null);
        CountDownLatch webhookLockEntered = new CountDownLatch(1);
        CountDownLatch releaseWebhook = new CountDownLatch(1);
        CountDownLatch reconciliationStarted = new CountDownLatch(1);
        AtomicReference<Thread> reconciliationThread = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> webhook = executor.submit(() -> payments.withPayPalWebhookOwnerLock(event, () -> {
                webhookLockEntered.countDown();
                await(releaseWebhook);
                return "webhook result";
            }));
            try {
                assertThat(webhookLockEntered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<PaymentResponse> reconciliation = executor.submit(() -> {
                    reconciliationThread.set(Thread.currentThread());
                    reconciliationStarted.countDown();
                    return payments.reconcile(orderId, "sync-reconcile");
                });
                assertThat(reconciliationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(reconciliationThread.get());
                assertThat(transactionEntered.getCount()).isEqualTo(1);

                releaseWebhook.countDown();

                assertThat(transactionEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(reconciliation.get(5, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.CAPTURED);
                assertThat(webhook.get(5, TimeUnit.SECONDS)).isEqualTo("webhook result");
            } finally {
                releaseWebhook.countDown();
            }
        }
    }

    @Test
    void captureWebhookOwnerLockUsesCheckoutOwner() throws Exception {
        UUID checkoutId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt(null, checkoutId, UUID.randomUUID(), PaymentState.CAPTURED,
                PaymentPhase.CAPTURE, false);
        attempt.setProviderOrderId("provider-order");
        attempt.setProviderCaptureId("provider-capture");
        PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        when(attempts.findByProviderCaptureId("provider-capture")).thenReturn(Optional.of(attempt));
        when(attempts.findByCheckoutId(checkoutId)).thenReturn(Optional.of(attempt));

        CountDownLatch transactionEntered = new CountDownLatch(1);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            transactionEntered.countDown();
            return new SimpleTransactionStatus();
        });
        PaymentServiceImpl payments = new PaymentServiceImpl(attempts, mock(PaymentRefundRepository.class),
                mock(PaymentWebhookEventRepository.class), transactionManager, mock(PaymentProvider.class),
                mock(FxConversionService.class), "https://example.test");
        PaymentWebhookEvent event = new PaymentWebhookEvent("event-1", "PAYMENT.CAPTURE.COMPLETED",
                "provider-capture", "COMPLETED", null, null, null, null);
        CountDownLatch webhookLockEntered = new CountDownLatch(1);
        CountDownLatch releaseWebhook = new CountDownLatch(1);
        CountDownLatch reconciliationStarted = new CountDownLatch(1);
        AtomicReference<Thread> reconciliationThread = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> webhook = executor.submit(() -> payments.withPayPalWebhookOwnerLock(event, () -> {
                webhookLockEntered.countDown();
                await(releaseWebhook);
                return "checkout webhook result";
            }));
            try {
                assertThat(webhookLockEntered.await(5, TimeUnit.SECONDS)).isTrue();
                Future<PaymentResponse> reconciliation = executor.submit(() -> {
                    reconciliationThread.set(Thread.currentThread());
                    reconciliationStarted.countDown();
                    return payments.reconcileCheckout(checkoutId, "sync-reconcile");
                });
                assertThat(reconciliationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                awaitBlocked(reconciliationThread.get());
                assertThat(transactionEntered.getCount()).isEqualTo(1);

                releaseWebhook.countDown();

                assertThat(transactionEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(reconciliation.get(5, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.CAPTURED);
                assertThat(webhook.get(5, TimeUnit.SECONDS)).isEqualTo("checkout webhook result");
            } finally {
                releaseWebhook.countDown();
            }
        }
    }

    @Test
    void ownerLockCallbackReturnsResultForUnmappedAndNonCaptureEvents() {
        PaymentServiceImpl payments = new PaymentServiceImpl(mock(PaymentAttemptRepository.class),
                mock(PaymentRefundRepository.class), mock(PaymentWebhookEventRepository.class),
                mock(PlatformTransactionManager.class), mock(PaymentProvider.class), mock(FxConversionService.class),
                "https://example.test");
        PaymentWebhookEvent unmatchedCapture = new PaymentWebhookEvent("event-unmatched",
                "PAYMENT.CAPTURE.COMPLETED", "capture-unmatched", "COMPLETED", "order-unmatched",
                null, null, null);
        PaymentWebhookEvent refund = new PaymentWebhookEvent("event-refund", "PAYMENT.CAPTURE.REFUNDED",
                "refund-1", "COMPLETED", null, null, null, null);
        Object unmatchedResult = new Object();
        Object refundResult = new Object();
        Supplier<Object> unmatchedAction = () -> unmatchedResult;
        Supplier<Object> refundAction = () -> refundResult;

        assertThat(payments.withPayPalWebhookOwnerLock(unmatchedCapture, unmatchedAction))
                .isSameAs(unmatchedResult);
        assertThat(payments.withPayPalWebhookOwnerLock(refund, refundAction)).isSameAs(refundResult);
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release webhook owner lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
