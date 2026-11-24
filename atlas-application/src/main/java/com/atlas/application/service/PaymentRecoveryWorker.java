package com.atlas.application.service;

import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryOperationType;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
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
    private static final String APPROVAL_RESUME_STAGE = "approval_resume";
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
    private final AtomicReference<Long> lastApprovalResumeFailureLogNanos = new AtomicReference<>();

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
        // 모든 Gauge 가 하나의 TTL 캐시 스냅샷을 공유한다.
        //
        // 이전 구현은 상태 4 + stage×상태 8 + stage 나이 3 + 전체 나이 1 = 16개의 Gauge 가
        // 각각 COUNT(*) 를 실행했다. Micrometer Gauge 는 스크레이프마다 값을 읽으므로,
        // Prometheus 스크레이프 빈도가 곧 DB 부하가 됐고 /actuator/prometheus 는 비인증이었다.
        // payment_recovery_job 은 append-only 라 스캔 비용이 영구히 증가한다.
        // Gauge 는 스크레이프마다 값을 읽으므로, 매번 DB 를 치지 않고 TTL 캐시를 통과한다.
        for (PaymentRecoveryJobStatus status : PaymentRecoveryJobStatus.values()) {
            Gauge.builder("atlas.payment.recovery.jobs", recovery,
                    service -> service.statusCount(status))
                    .tag("status", status.name())
                    .register(meters);
        }
        for (PaymentRecoveryWorkStage stage : PaymentRecoveryWorkStage.values()) {
            for (PaymentRecoveryJobStatus status : PaymentRecoveryJobStatus.values()) {
                Gauge.builder("atlas.payment.recovery.stage.jobs", recovery,
                        service -> service.stageCount(stage, status))
                        .tag("work_stage", stage.name())
                        .tag("status", status.name())
                        .register(meters);
            }
            Gauge.builder("atlas.payment.recovery.stage.oldest_due_age_seconds", recovery,
                    service -> service.stageOldestDueAgeSeconds(stage))
                    .tag("work_stage", stage.name())
                    .register(meters);
        }
        Gauge.builder("atlas.payment.recovery.oldest_due_age_seconds", recovery,
                service -> service.oldestDueAgeAnyStage())
                .register(meters);
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
        try {
            // 승인 화면에서 이탈한 결제는 callback이 없어 WAITING_FOR_BUYER에 머문다. deadline이
            // 지난 job을 되돌려 provider 상태 확인과 owner 적용을 다시 태운다.
            int resumed = recovery.resumeExpiredApprovalWaits(batchSize);
            if (resumed > 0) {
                Counter.builder("atlas.payment.recovery.approval.resumed").register(meters)
                        .increment(resumed);
            }
        } catch (RuntimeException exception) {
            recordPollFailure(APPROVAL_RESUME_STAGE, exception, lastApprovalResumeFailureLogNanos);
            return;
        }
        for (int index = 0; index < batchSize; index++) {
            List<PaymentRecoveryResponse.Job> claimed;
            try {
                claimed = recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge);
            } catch (RuntimeException exception) {
                recordPollFailure(CLAIM_POLL_STAGE, exception, lastClaimFailureLogNanos);
                return;
            }
            if (claimed.isEmpty()) return;
            PaymentRecoveryResponse.Job job = claimed.getFirst();
            counter("claimed", job.operationType()).increment();
            process(job);
        }
    }

    private void process(PaymentRecoveryResponse.Job job) {
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

    private void recoverPayment(PaymentRecoveryResponse.Job job) {
        PaymentResponse current = payments.findByAttemptId(job.operationId());
        if (current == null) {
            recovery.markNeedsReview(job, "PAYMENT_ATTEMPT_MISSING");
            counter("needs_review", job.operationType()).increment();
            return;
        }
        if (job.workStage() == PaymentRecoveryWorkStage.APPLY
                || (current.state().isTerminal() && !current.authorizationActive())) {
            reconcilePaymentOwner(job, current);
            PaymentResponse result = payments.findByAttemptId(job.operationId());
            settle(job, result.state().isTerminal() && !result.authorizationActive(), awaitingBuyer(result));
            return;
        }
        if (awaitingBuyer(current) && !approvalWindowExpired(current)) {
            if (current.approvalExpiresAt() != null && recovery.waitForBuyer(job)) {
                return;
            }
            // deadline 없는 대기 행은 재개 sweep이 확정할 수 없어 영구 미확정으로 남는다.
            // 그렇게 남기느니 재시도/운영자 검토로 보낸다.
            throw new IllegalStateException("Approval wait has no deadline");
        }
        PaymentResponse resolved = approvalWindowExpired(current)
                ? payments.resolveExpiredApproval(job.operationId(), "payment-recovery-" + job.operationId())
                : current;
        reconcilePaymentOwner(job, resolved);
        PaymentResponse result = payments.findByAttemptId(job.operationId());
        settle(job, result.state().isTerminal() && !result.authorizationActive(), awaitingBuyer(result));
    }

    /**
     * 승인 대기 deadline이 지났는지 확인한다. 지났으면 구매자 신호를 기다리지 않고 provider
     * 상태를 확정해야 하므로, {@code WAITING_FOR_BUYER}로 다시 보내지 않는다.
     *
     * <p>deadline이 없는 대기 상태는 정상 경로가 만들지 않는다. 그런 행은 재개 sweep도 확정
     * 대상으로 보므로(NULL을 즉시 due로 처리), 여기서도 같은 판단을 유지해야 두 경로가
     * 서로 되돌리며 무한 재시도하는 일이 없다.
     */
    private boolean approvalWindowExpired(PaymentResponse payment) {
        return payment.state() == PaymentState.AUTHORIZATION_PENDING
                && (payment.approvalExpiresAt() == null
                    || !Instant.now().isBefore(payment.approvalExpiresAt()));
    }

    private void reconcilePaymentOwner(PaymentRecoveryResponse.Job job, PaymentResponse payment) {
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

    private void recoverRefund(PaymentRecoveryResponse.Job job) {
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

    private void settle(PaymentRecoveryResponse.Job job, boolean terminal, boolean awaitingBuyer) {
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
    private void recordCompletion(PaymentRecoveryResponse.Job job) {
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
            OrderViewResponse.Order order = orders.find(payment.orderId());
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
