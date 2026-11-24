package com.atlas.application.service;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryOperationType;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.model.PaymentRefundState;
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
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
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
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
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
        OrderViewResponse.Order order = mock(OrderViewResponse.Order.class);
        when(order.state()).thenReturn(OrderState.PAYMENT_PENDING);
        when(orders.find(orderId)).thenReturn(order);
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        // 정상 대기 상태는 진입 시 deadline을 남긴다. deadline이 없으면 "확정 대상"이므로
        // 이 테스트는 미래 deadline을 가진 정상 대기를 표현한다.
        PaymentResponse awaitingApproval = new PaymentResponse(
                orderId, null, attemptId, PaymentState.AUTHORIZATION_PENDING, false,
                "provider-order-1", null, "https://provider.test/approve",
                Instant.now().plus(Duration.ofMinutes(30)));
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
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
        PaymentResponse awaitingApproval = new PaymentResponse(
                orderId, null, attemptId, PaymentState.AUTHORIZATION_PENDING, false,
                "provider-order-1", null, "https://provider.test/approve",
                Instant.now().plus(Duration.ofMinutes(30)));
        OrderViewResponse.Order cancellingOrder = mock(OrderViewResponse.Order.class);
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
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_REFUND, refundId);
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
        PaymentRecoveryResponse.Job job = job(PaymentRecoveryOperationType.PAYMENT_ATTEMPT, attemptId);
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

    @Test
    void backlogGaugesKeepPrometheusNamesAndStringTagsWhileReadingByEnum() {
        when(recovery.statusCount(PaymentRecoveryJobStatus.NEEDS_REVIEW)).thenReturn(3L);
        when(recovery.stageCount(PaymentRecoveryWorkStage.APPLY, PaymentRecoveryJobStatus.PENDING)).thenReturn(2L);
        when(recovery.stageOldestDueAgeSeconds(PaymentRecoveryWorkStage.PROVIDER)).thenReturn(7.5);

        assertThat(meters.get("atlas.payment.recovery.jobs").tag("status", "NEEDS_REVIEW").gauge().value())
                .isEqualTo(3.0);
        assertThat(meters.get("atlas.payment.recovery.stage.jobs")
                .tags("work_stage", "APPLY", "status", "PENDING").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("atlas.payment.recovery.stage.oldest_due_age_seconds")
                .tag("work_stage", "PROVIDER").gauge().value()).isEqualTo(7.5);
    }

    private static PaymentRecoveryResponse.Job job(PaymentRecoveryOperationType type, UUID operationId) {
        return new PaymentRecoveryResponse.Job(
                UUID.randomUUID(), type, PaymentRecoveryWorkStage.PROVIDER,
                operationId, 1, Instant.now(), UUID.randomUUID());
    }

    private static PaymentRefundResponse refund(UUID refundId, UUID attemptId, PaymentRefundState status) {
        return new PaymentRefundResponse(
                refundId, attemptId, "capture-1", 100, "KRW", null, status, Instant.now());
    }
}
