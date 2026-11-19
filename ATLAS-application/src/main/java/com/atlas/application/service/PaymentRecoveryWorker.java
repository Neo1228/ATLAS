package com.atlas.application.service;

import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentRecoveryJob;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.persistence.state.PaymentRecoveryJobStatus;
import com.atlas.payment.persistence.state.PaymentRecoveryOperationType;
import com.atlas.payment.persistence.state.PaymentRefundState;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentRecoveryWorkStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "atlas.payment.recovery.worker.enabled", havingValue = "true")
public class PaymentRecoveryWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentRecoveryWorker.class);
    private static final String TRANSIENT_FAILURE = "TRANSIENT_FAILURE";
    private static final String POLL_FAILURES_METRIC = "atlas.payment.recovery.poll.failures";
    private static final String EXHAUSTED_POLL_STAGE = "expire_exhausted";
    private static final String CLAIM_POLL_STAGE = "claim_due";
    private static final long POLL_FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private final PaymentRecoveryService recovery;
    private final PaymentService payments;
    private final OrderService orders;
    private final CheckoutPaymentService checkouts;
    private final OrderRefundService refunds;
    private final MeterRegistry meters;
    private final int batchSize;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Duration maxAge;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final AtomicReference<Long> lastExhaustedFailureLogNanos = new AtomicReference<>();
    private final AtomicReference<Long> lastClaimFailureLogNanos = new AtomicReference<>();

    public PaymentRecoveryWorker(
            PaymentRecoveryService recovery,
            PaymentService payments,
            OrderService orders,
            CheckoutPaymentService checkouts,
            OrderRefundService refunds,
            MeterRegistry meters,
            @Value("${atlas.payment.recovery.batch-size:10}") int batchSize,
            @Value("${atlas.payment.recovery.lease-duration:PT60S}") Duration leaseDuration,
            @Value("${atlas.payment.recovery.max-attempts:8}") int maxAttempts,
            @Value("${atlas.payment.recovery.max-age:PT24H}") Duration maxAge,
            @Value("${atlas.payment.recovery.base-backoff:PT1S}") Duration baseBackoff,
            @Value("${atlas.payment.recovery.max-backoff:PT15M}") Duration maxBackoff) {
        this.recovery = Objects.requireNonNull(recovery);
        this.payments = Objects.requireNonNull(payments);
        this.orders = Objects.requireNonNull(orders);
        this.checkouts = Objects.requireNonNull(checkouts);
        this.refunds = Objects.requireNonNull(refunds);
        this.meters = Objects.requireNonNull(meters);
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1
                || leaseDuration.isNegative() || leaseDuration.isZero()
                || maxAge.isNegative() || maxAge.isZero()
                || baseBackoff.isNegative() || baseBackoff.isZero()
                || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("Payment recovery worker settings are invalid");
        }
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.maxAttempts = maxAttempts;
        this.maxAge = maxAge;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        for (PaymentRecoveryJobStatus status : PaymentRecoveryJobStatus.values()) {
            Gauge.builder("atlas.payment.recovery.jobs", recovery, repository -> repository.count(status))
                    .tag("status", status.name())
                    .register(meters);
        }
        for (PaymentRecoveryWorkStage stage : PaymentRecoveryWorkStage.values()) {
            for (PaymentRecoveryJobStatus status : PaymentRecoveryJobStatus.values()) {
                Gauge.builder("atlas.payment.recovery.stage.jobs", recovery,
                        service -> service.count(status, stage))
                        .tag("work_stage", stage.name())
                        .tag("status", status.name())
                        .register(meters);
            }
            Gauge.builder("atlas.payment.recovery.stage.oldest_due_age_seconds",
                    recovery, service -> service.oldestDueAgeSeconds(stage))
                    .tag("work_stage", stage.name())
                    .register(meters);
        }
        Gauge.builder("atlas.payment.recovery.oldest_due_age_seconds",
                recovery, PaymentRecoveryService::oldestDueAgeSeconds).register(meters);
    }

    @Scheduled(
            initialDelayString = "${atlas.payment.recovery.initial-poll-delay:PT2S}",
            fixedDelayString = "${atlas.payment.recovery.poll-interval:PT1S}",
            scheduler = "paymentRecoveryTaskScheduler")
    public void poll() {
        try {
            recovery.expireExhausted(maxAttempts, maxAge);
        } catch (RuntimeException exception) {
            recordPollFailure(EXHAUSTED_POLL_STAGE, exception, lastExhaustedFailureLogNanos);
            return;
        }
        for (int index = 0; index < batchSize; index++) {
            List<PaymentRecoveryJob> claimed;
            try {
                claimed = recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge);
            } catch (RuntimeException exception) {
                recordPollFailure(CLAIM_POLL_STAGE, exception, lastClaimFailureLogNanos);
                return;
            }
            if (claimed.isEmpty()) return;
            PaymentRecoveryJob job = claimed.getFirst();
            counter("claimed", job.operationType()).increment();
            process(job);
        }
    }

    private void process(PaymentRecoveryJob job) {
        Timer.Sample sample = Timer.start(meters);
        try {
            if (job.operationType() == PaymentRecoveryOperationType.PAYMENT_ATTEMPT) {
                recoverPayment(job);
            } else {
                recoverRefund(job);
            }
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof PaymentException paymentException
                    ? paymentException.code().name() : TRANSIENT_FAILURE;
            LOGGER.warn("Payment recovery failed jobId={} operationType={} failureType={}",
                    job.recoveryJobId(), job.operationType(), exception.getClass().getSimpleName());
            try {
                if (recovery.reschedule(job, backoff(job.attemptCount()), maxAttempts, maxAge, errorCode)) {
                    counter("failed", job.operationType()).increment();
                }
            } catch (RuntimeException persistenceFailure) {
                LOGGER.error("Unable to persist payment recovery outcome jobId={} failureType={}",
                        job.recoveryJobId(), persistenceFailure.getClass().getSimpleName());
            }
        } finally {
            sample.stop(Timer.builder("atlas.payment.recovery.duration")
                    .tag("operation_type", job.operationType().name())
                    .tag("work_stage", job.workStage().name()).register(meters));
        }
    }

    private void recoverPayment(PaymentRecoveryJob job) {
        PaymentResponse current = payments.findByAttemptId(job.operationId());
        if (current == null) {
            recovery.markNeedsReview(job, "PAYMENT_ATTEMPT_MISSING");
            counter("needs_review", job.operationType()).increment();
            return;
        }
        if (job.workStage() == PaymentRecoveryWorkStage.APPLY
                || (terminal(current.state()) && !current.authorizationActive())) {
            reconcilePaymentOwner(job, current);
            PaymentResponse result = payments.findByAttemptId(job.operationId());
            settle(job, terminal(result.state()) && !result.authorizationActive(), awaitingBuyer(result));
            return;
        }
        if (awaitingBuyer(current)) {
            recovery.waitForBuyer(job);
            return;
        }
        reconcilePaymentOwner(job, current);
        PaymentResponse result = payments.findByAttemptId(job.operationId());
        settle(job, terminal(result.state()) && !result.authorizationActive(), awaitingBuyer(result));
    }

    private void reconcilePaymentOwner(PaymentRecoveryJob job, PaymentResponse payment) {
        String idempotencyKey = "payment-recovery-" + job.operationId();
        if (payment.orderId() != null) {
            orders.reconcilePayment(payment.orderId(), idempotencyKey);
        } else if (payment.checkoutId() != null) {
            checkouts.reconcile(payment.checkoutId(), idempotencyKey);
        } else {
            recovery.markNeedsReview(job, "PAYMENT_OWNER_MISSING");
            counter("needs_review", job.operationType()).increment();
        }
    }

    private void recoverRefund(PaymentRecoveryJob job) {
        PaymentRefundResponse current = payments.findRefundOperation(job.operationId());
        if (current == null) {
            recovery.markNeedsReview(job, "REFUND_OPERATION_MISSING");
            counter("needs_review", job.operationType()).increment();
            return;
        }
        if (!terminal(current.status())) {
            payments.retryRefundOperation(job.operationId());
        }
        refunds.reconcilePayPalRefund(job.operationId());
        PaymentRefundResponse result = payments.findRefundOperation(job.operationId());
        settle(job, terminal(result.status()), false);
    }

    private void settle(PaymentRecoveryJob job, boolean terminal, boolean awaitingBuyer) {
        if (terminal) {
            recordCompletion(job);
        } else if (awaitingBuyer) {
            recovery.waitForBuyer(job);
        } else if (recovery.reschedule(job, backoff(job.attemptCount()), maxAttempts, maxAge,
                "PROVIDER_RESULT_UNRESOLVED")) {
            counter("rescheduled", job.operationType()).increment();
        }
    }

    /**
     * lease로 보호되는 완료 처리. 다른 replica가 lease를 가져갔으면 {@code complete}가
     * false를 반환한다(provider 호출 중에는 60초 lease가 갱신되지 않는다).
     * 무조건 집계하면 실제 job 수보다 많은 완료를 보고하게 된다.
     */
    private void recordCompletion(PaymentRecoveryJob job) {
        if (recovery.complete(job)) {
            counter("completed", job.operationType()).increment();
        } else {
            counter("completion_fenced", job.operationType()).increment();
        }
    }

    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long baseMillis = baseBackoff.toMillis();
        long capMillis = maxBackoff.toMillis();
        long exponential = baseMillis > (capMillis >> exponent)
                ? capMillis : Math.min(capMillis, baseMillis << exponent);
        long half = Math.max(1, exponential / 2);
        long jitter = ThreadLocalRandom.current().nextLong(half);
        return Duration.ofMillis(Math.min(capMillis, half + jitter));
    }

    private void recordPollFailure(String stage, RuntimeException exception, AtomicReference<Long> lastLogNanos) {
        Counter.builder(POLL_FAILURES_METRIC).tag("stage", stage).register(meters).increment();
        long now = System.nanoTime();
        Long previous = lastLogNanos.get();
        if ((previous == null || now - previous >= POLL_FAILURE_LOG_INTERVAL_NANOS)
                && lastLogNanos.compareAndSet(previous, now)) {
            LOGGER.warn("Payment recovery poll failed; stage={} failureType={}", stage,
                    exception.getClass().getSimpleName());
        }
    }

    private Counter counter(String outcome, PaymentRecoveryOperationType type) {
        return Counter.builder("atlas.payment.recovery." + outcome)
                .tag("operation_type", type.name())
                .register(meters);
    }

    private static boolean terminal(PaymentState state) {
        return state == PaymentState.CAPTURED || state == PaymentState.DECLINED
                || state == PaymentState.VOIDED;
    }

    private static boolean terminal(PaymentRefundState state) {
        return state == PaymentRefundState.COMPLETED || state == PaymentRefundState.FAILED;
    }

    private boolean awaitingBuyer(PaymentResponse payment) {
        if (payment.state() != PaymentState.AUTHORIZATION_PENDING
                || payment.providerOrderId() == null || payment.approvalUrl() == null
                || payment.approvalUrl().isBlank()) {
            return false;
        }
        if (payment.orderId() != null) {
            OrderResponse order = orders.find(payment.orderId());
            return order != null && (order.state() == OrderState.PAYMENT_SUBMITTED
                    || order.state() == OrderState.PAYMENT_PENDING
                    || order.state() == OrderState.PAYMENT_UNKNOWN);
        }
        if (payment.checkoutId() != null) {
            CheckoutResponse checkout = checkouts.find(payment.checkoutId());
            return checkout != null && (checkout.state() == BuyerCheckoutState.PAYMENT_PENDING
                    || checkout.state() == BuyerCheckoutState.PAYMENT_UNKNOWN);
        }
        return false;
    }
}
