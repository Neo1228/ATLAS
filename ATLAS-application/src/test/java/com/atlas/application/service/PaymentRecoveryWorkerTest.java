package com.atlas.application.service;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentRecoveryJob;
import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PaymentRecoveryWorkerTest {
    private final PaymentRecoveryService recovery = mock(PaymentRecoveryService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderService orders = mock(OrderService.class);
    private final CheckoutPaymentService checkouts = mock(CheckoutPaymentService.class);
    private final OrderRefundService refunds = mock(OrderRefundService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PaymentRecoveryWorker worker = new PaymentRecoveryWorker(
            recovery, payments, orders, checkouts, refunds, meters, 10,
            Duration.ofSeconds(60), 8, Duration.ofHours(24), Duration.ofSeconds(1), Duration.ofMinutes(15));

    @Test
    void unresolvedPaymentReconcilesThroughItsOwningOrderAndStableRecoveryKey() {
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        PaymentResponse unknown = new PaymentResponse(
                orderId, null, attemptId, PaymentState.UNKNOWN, false, null, null, null);
        PaymentResponse captured = new PaymentResponse(
                orderId, null, attemptId, PaymentState.CAPTURED, false, null, "capture-1", null);
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findByAttemptId(attemptId)).thenReturn(unknown, captured);
        when(recovery.complete(job)).thenReturn(true);

        worker.poll();

        verify(orders).reconcilePayment(orderId, "payment-recovery-" + attemptId);
        verify(recovery).complete(job);
    }


    @Test
    void checkoutPaymentReconcilesThroughParentCheckoutService() {
        UUID attemptId = UUID.randomUUID();
        UUID checkoutId = UUID.randomUUID();
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        PaymentResponse unknown = new PaymentResponse(
                null, checkoutId, attemptId, PaymentState.UNKNOWN, false, null, null, null);
        PaymentResponse captured = new PaymentResponse(
                null, checkoutId, attemptId, PaymentState.CAPTURED, false, null, "capture-1", null);
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findByAttemptId(attemptId)).thenReturn(unknown, captured);
        when(recovery.complete(job)).thenReturn(true);

        worker.poll();

        verify(checkouts).reconcile(checkoutId, "payment-recovery-" + attemptId);
        verify(recovery).complete(job);
    }
    @Test
    void authorizationWaitingForBuyerIsNotAutomaticallyRetried() {
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OrderResponse order = mock(OrderResponse.class);
        when(order.state()).thenReturn(OrderState.PAYMENT_PENDING);
        when(orders.find(orderId)).thenReturn(order);
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        PaymentResponse awaitingApproval = new PaymentResponse(
                orderId, null, attemptId, PaymentState.AUTHORIZATION_PENDING, false,
                "provider-order-1", null, "https://provider.test/approve");
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findByAttemptId(attemptId)).thenReturn(awaitingApproval);
        when(recovery.waitForBuyer(job)).thenReturn(true);

        worker.poll();

        verify(recovery).waitForBuyer(job);
        verify(orders, never()).reconcilePayment(any(), any());
        verify(checkouts, never()).reconcile(any(), any());
    }


    @Test
    void cancellationReconciliationTakesPrecedenceOverBuyerApprovalWait() {
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        PaymentResponse awaitingApproval = new PaymentResponse(
                orderId, null, attemptId, PaymentState.AUTHORIZATION_PENDING, false,
                "provider-order-1", null, "https://provider.test/approve");
        OrderResponse cancellingOrder = mock(OrderResponse.class);
        when(cancellingOrder.state()).thenReturn(OrderState.CANCEL_PENDING);
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findByAttemptId(attemptId)).thenReturn(awaitingApproval, awaitingApproval);
        when(orders.find(orderId)).thenReturn(cancellingOrder, cancellingOrder);

        worker.poll();

        verify(orders).reconcilePayment(orderId, "payment-recovery-" + attemptId);
        verify(recovery, never()).waitForBuyer(job);
    }

    @Test
    void unresolvedRefundUsesTheOrderRefundRecoveryContract() {
        UUID refundId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_REFUND, refundId);
        PaymentRefundResponse pending = refund(refundId, attemptId, PaymentRefundState.UNKNOWN);
        PaymentRefundResponse completed = refund(refundId, attemptId, PaymentRefundState.COMPLETED);
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findRefundOperation(refundId)).thenReturn(pending, completed);
        when(recovery.complete(job)).thenReturn(true);

        worker.poll();

        verify(refunds).reconcilePayPalRefund(refundId);
        verify(recovery).complete(job);
    }

    @Test
    void recordsFailureToMarkExhaustedJobsWithoutClaiming() {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(recovery).expireExhausted(8, Duration.ofHours(24));

        worker.poll();

        assertThat(meters.counter("atlas.payment.recovery.poll.failures", "stage", "expire_exhausted").count())
                .isEqualTo(1);
        verify(recovery, never()).claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)),
                eq(8), eq(Duration.ofHours(24)));
    }

    @Test
    void recordsFailureToClaimDueJobs() {
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24))))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        worker.poll();

        assertThat(meters.counter("atlas.payment.recovery.poll.failures", "stage", "claim_due").count())
                .isEqualTo(1);
        verify(payments, never()).findByAttemptId(any(UUID.class));
    }

    @Test
    void completedCounterIgnoresCompletionFencedByAnotherReplica() {
        UUID attemptId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentRecoveryJob job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        // provider 호출이 60초 lease를 넘겨 다른 replica가 이미 lease를 보유한 상태다.
        PaymentResponse captured = new PaymentResponse(
                orderId, null, attemptId, PaymentState.CAPTURED, false, null, "capture-1", null);
        when(recovery.claimNextDue(eq(1), any(UUID.class), eq(Duration.ofSeconds(60)), eq(8),
                eq(Duration.ofHours(24)))).thenReturn(List.of(job)).thenReturn(List.of());
        when(payments.findByAttemptId(attemptId)).thenReturn(captured);
        when(recovery.complete(job)).thenReturn(false);

        worker.poll();

        assertThat(meters.counter("atlas.payment.recovery.completed",
                "operation_type", "PAYMENT_ATTEMPT").count()).isZero();
        assertThat(meters.counter("atlas.payment.recovery.completion_fenced",
                "operation_type", "PAYMENT_ATTEMPT").count()).isEqualTo(1);
    }

    private static PaymentRecoveryJob job(PaymentRecoveryOperationType type, UUID operationId) {
        return new PaymentRecoveryJob(
                UUID.randomUUID(), type, PaymentRecoveryWorkStage.PROVIDER,
                operationId, 1, Instant.now(), UUID.randomUUID());
    }

    private static PaymentRefundResponse refund(UUID refundId, UUID attemptId, PaymentRefundState status) {
        return new PaymentRefundResponse(
                refundId, attemptId, "capture-1", 100, "KRW", null, status, Instant.now());
    }
}
