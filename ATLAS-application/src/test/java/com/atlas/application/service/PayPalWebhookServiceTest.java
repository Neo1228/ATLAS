package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.admission.service.AdmissionService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.repository.JdbcBuyerLockRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.impl.OrderServiceImpl;
import com.atlas.catalog.service.SellerSkuService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PayPalWebhookServiceTest {
    @Test
    void captureWebhookTransactionRunsInsideOwnerLockAndReconciliationRunsAfterRelease() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        PaymentService payments = mock(PaymentService.class);
        OrderService orders = mock(OrderService.class);
        PaymentWebhookEvent event = captureEvent("event-1", "order-1", "capture-1");
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AtomicBoolean ownerLockHeld = new AtomicBoolean();
        when(payments.verifyPayPalWebhook(any(), eq("{}"))).thenReturn(event);
        doAnswer(invocation -> {
            ownerLockHeld.set(true);
            try {
                return invocation.<Supplier<?>>getArgument(1).get();
            } finally {
                ownerLockHeld.set(false);
            }
        }).when(payments).withPayPalWebhookOwnerLock(eq(event), any());
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertThat(ownerLockHeld).isTrue();
            return new SimpleTransactionStatus();
        });
        when(payments.processVerifiedPayPalWebhook(event)).thenAnswer(invocation -> {
            assertThat(ownerLockHeld).isTrue();
            return new PaymentWebhookResult(false, true, null, null, attemptId, true);
        });
        when(payments.findByAttemptId(attemptId)).thenAnswer(invocation -> {
            assertThat(ownerLockHeld).isFalse();
            return new PaymentResponse(orderId, null, attemptId, PaymentState.CAPTURED, false);
        });
        doAnswer(invocation -> {
            assertThat(ownerLockHeld).isFalse();
            return null;
        }).when(orders).reconcilePayment(orderId, event.eventId());

        PayPalWebhookService webhookService = new PayPalWebhookService(transactionManager, payments, orders,
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class));
        webhookService.handle(signature(), "{}");

        verify(payments).withPayPalWebhookOwnerLock(eq(event), any());
        verify(orders).reconcilePayment(orderId, event.eventId());
    }

    @Test
    void webhookAndRealOrderOwnerLockCompleteWithoutPaymentToOrderLockCycle() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        PaymentWebhookEvent event = captureEvent("event-lock-cycle", "provider-order", "capture-lock-cycle");
        PaymentResponse unknown = new PaymentResponse(orderId, null, attemptId, PaymentState.UNKNOWN, false);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());

        PaymentService payments = mock(PaymentService.class);
        ReentrantLock paymentOwnerLock = new ReentrantLock();
        CountDownLatch orderOwnerWaitingForPayment = new CountDownLatch(1);
        CountDownLatch releaseOrderPaymentRead = new CountDownLatch(1);
        CountDownLatch webhookReachedOrderReconciliation = new CountDownLatch(1);
        AtomicBoolean webhookEnteredOrderReconciliationWithPaymentLock = new AtomicBoolean();
        AtomicReference<Thread> webhookThread = new AtomicReference<>();
        AtomicInteger paymentFindCalls = new AtomicInteger();

        OrderRepository orderRepository = mock(OrderRepository.class);
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.orderId()).thenReturn(orderId);
        when(order.source()).thenReturn(OrderSource.CART);
        when(order.checkoutId()).thenReturn(null);
        when(order.state()).thenReturn(OrderState.PAYMENT_SUBMITTED);
        when(order.checkoutExpiresAt()).thenReturn(Instant.now());
        when(order.paymentAttemptId()).thenReturn(attemptId);
        when(order.fulfillmentLocationId()).thenReturn(null);
        when(order.sellerCouponCode()).thenReturn(null);
        when(order.couponDiscountWon()).thenReturn(0L);
        OrderItemRepository orderItems = mock(OrderItemRepository.class);
        when(orderItems.findByOrderOrderIdOrderBySkuIdAsc(orderId)).thenReturn(List.of());
        InventoryService inventory = mock(InventoryService.class);
        when(inventory.findReservations(orderId)).thenReturn(List.of());
        when(orderRepository.findById(orderId)).thenAnswer(invocation -> {
            if (Thread.currentThread() == webhookThread.get()) {
                webhookEnteredOrderReconciliationWithPaymentLock.set(paymentOwnerLock.isHeldByCurrentThread());
                webhookReachedOrderReconciliation.countDown();
            }
            return Optional.of(order);
        });
        when(orderRepository.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));

        when(payments.verifyPayPalWebhook(any(), eq("{}"))).thenReturn(event);
        doAnswer(invocation -> {
            paymentOwnerLock.lock();
            try {
                return invocation.<Supplier<?>>getArgument(1).get();
            } finally {
                paymentOwnerLock.unlock();
            }
        }).when(payments).withPayPalWebhookOwnerLock(eq(event), any());
        when(payments.processVerifiedPayPalWebhook(event))
                .thenReturn(new PaymentWebhookResult(false, true, null, null, attemptId, true));
        when(payments.findByAttemptId(attemptId)).thenReturn(
                new PaymentResponse(orderId, null, attemptId, PaymentState.CAPTURED, false));
        when(payments.find(orderId)).thenAnswer(invocation -> {
            if (paymentFindCalls.getAndIncrement() == 0) {
                orderOwnerWaitingForPayment.countDown();
                await(releaseOrderPaymentRead);
                return null;
            }
            return unknown;
        });
        when(payments.reconcile(eq(orderId), anyString())).thenAnswer(invocation -> {
            lockInterruptibly(paymentOwnerLock);
            try {
                return unknown;
            } finally {
                paymentOwnerLock.unlock();
            }
        });

        OrderService orders = new OrderServiceImpl(transactionManager, orderRepository,
                orderItems, mock(JdbcBuyerLockRepository.class),
                mock(OrderFulfillmentService.class), mock(AdmissionService.class), inventory,
                payments, mock(SellerSkuService.class), mock(SettlementService.class), Clock.systemUTC(),
                Duration.ofMinutes(10));
        PayPalWebhookService webhookService = new PayPalWebhookService(transactionManager, payments, orders,
                mock(CheckoutPaymentService.class), mock(OrderRefundService.class));
        ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "paypal-webhook-lock-regression");
            thread.setDaemon(true);
            return thread;
        });
        Future<OrderResponse> synchronousReconciliation = null;
        Future<?> webhook = null;
        try {
            synchronousReconciliation = executor.submit(
                    () -> orders.reconcilePayment(orderId, "sync-order-reconcile"));
            assertThat(orderOwnerWaitingForPayment.await(5, TimeUnit.SECONDS)).isTrue();

            webhook = executor.submit(() -> {
                webhookThread.set(Thread.currentThread());
                webhookService.handle(signature(), "{}");
            });
            assertThat(webhookReachedOrderReconciliation.await(5, TimeUnit.SECONDS)).isTrue();
            releaseOrderPaymentRead.countDown();

            assertThat(synchronousReconciliation.get(5, TimeUnit.SECONDS)).isNotNull();
            webhook.get(5, TimeUnit.SECONDS);
            assertThat(webhookEnteredOrderReconciliationWithPaymentLock).isFalse();
        } finally {
            releaseOrderPaymentRead.countDown();
            if (synchronousReconciliation != null) synchronousReconciliation.cancel(true);
            if (webhook != null) webhook.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static PaymentWebhookEvent captureEvent(String eventId, String providerOrderId, String captureId) {
        return new PaymentWebhookEvent(eventId, "PAYMENT.CAPTURE.COMPLETED", captureId,
                "COMPLETED", providerOrderId, null, null, null);
    }

    private static PaymentWebhookSignature signature() {
        return new PaymentWebhookSignature(null, null, null, null, null);
    }

    private static void lockInterruptibly(ReentrantLock lock) {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring payment owner lock", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out while waiting to release order payment read");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
