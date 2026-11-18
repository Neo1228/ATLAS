package com.atlas.application.service;

import com.atlas.payment.dto.response.PaymentWebhookResult;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 영속화된 검증 이벤트를 처리하며 provider API를 호출하지 않습니다. Issue #277. */
@Component
@ConditionalOnProperty(
        name = "atlas.payment.paypal.webhook-retry.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PayPalWebhookRetryWorker {
    private static final String RUNS_METRIC = "atlas.paypal.webhook.retry.runs";
    private static final String CLAIMED_METRIC = "atlas.paypal.webhook.retry.claimed";
    private static final String REAPED_METRIC = "atlas.paypal.webhook.retry.reaped";
    private static final String OUTCOMES_METRIC = "atlas.paypal.webhook.retry.outcomes";
    private static final String DURATION_METRIC = "atlas.paypal.webhook.retry.duration";
    private static final String ATTEMPT_DURATION_METRIC = "atlas.paypal.webhook.retry.attempt.duration";
    private static final String OLDEST_PENDING_AGE_METRIC = "atlas.paypal.webhook.retry.oldest_pending_age";
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookRetryWorker.class);
    private final PayPalWebhookRetryRepository retries;
    private final PayPalWebhookService webhooks;
    private final MeterRegistry meters;
    private final AtomicLong lastPollFailureLogNanos = new AtomicLong();

    public PayPalWebhookRetryWorker(PayPalWebhookRetryRepository retries, PayPalWebhookService webhooks,
            MeterRegistry meters) {
        this.retries = retries;
        this.webhooks = webhooks;
        this.meters = meters;
    }

    @Scheduled(initialDelayString = "${atlas.payment.paypal.webhook-retry.worker.initial-delay:PT1S}",
            fixedDelayString = "${atlas.payment.paypal.webhook-retry.worker.poll-interval:PT1S}")
    public void retryDueEvents() {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            PayPalWebhookRetryRepository.PollResult poll = retries.pollDue();
            List<PayPalWebhookRetryRepository.ClaimedRetry> claimed = poll.claimed();
            if (poll.reapedCount() > 0) {
                Counter.builder(REAPED_METRIC).register(meters).increment(poll.reapedCount());
            }
            Counter claimedCounter = Counter.builder(CLAIMED_METRIC).register(meters);
            if (!claimed.isEmpty()) claimedCounter.increment(claimed.size());
            recordOldestPendingAge();
            for (PayPalWebhookRetryRepository.ClaimedRetry retry : claimed) {
                Timer.Sample attemptSample = Timer.start(meters);
                try {
                    PaymentWebhookResult result;
                    try {
                        result = webhooks.processVerified(retry.event());
                    } catch (RuntimeException failure) {
                        recordTransientFailure(retry, failure);
                        continue;
                    }

                    try {
                        boolean recorded;
                        String resultOutcome;
                        if (result.accepted() || result.duplicate()) {
                            recorded = retries.complete(retry.event().eventId(), retry.leaseOwner());
                            resultOutcome = result.duplicate() ? "duplicate" : "accepted";
                        } else if (result.retryable()) {
                            recorded = retries.reschedule(retry.event().eventId(), retry.attempt(),
                                    retry.leaseOwner(), PayPalWebhookRetryRepository.RetryFailure.CORRELATION_MISS,
                                    "Verified event is not correlated yet");
                            resultOutcome = "rescheduled";
                        } else {
                            recorded = retries.reject(retry.event().eventId(), retry.attempt(),
                                    retry.leaseOwner(), "Verified event failed permanent validation");
                            resultOutcome = "rejected";
                        }
                        if (!recorded) {
                            logStaleClaim(retry);
                            recordOutcome("stale_claim");
                        } else {
                            recordOutcome(resultOutcome);
                        }
                    } catch (RuntimeException failure) {
                        recordOutcome("record_failure");
                        log.error("Unable to record PayPal webhook outcome for event {} (attempt {}, failure type {})",
                                retry.event().eventId(), retry.attempt(), failure.getClass().getSimpleName());
                    }
                } finally {
                    attemptSample.stop(Timer.builder(ATTEMPT_DURATION_METRIC).register(meters));
                }
            }
        } catch (RuntimeException failure) {
            outcome = "failure";
            warnPollFailure(failure);
        } finally {
            Counter.builder(RUNS_METRIC).tag("outcome", outcome).register(meters).increment();
            sample.stop(Timer.builder(DURATION_METRIC).register(meters));
        }
    }

    private void recordTransientFailure(PayPalWebhookRetryRepository.ClaimedRetry retry, RuntimeException failure) {
        log.warn("PayPal webhook processing failed for event {} (attempt {}, failure type {})",
                retry.event().eventId(), retry.attempt(), failure.getClass().getSimpleName());
        try {
            boolean recorded = retries.reschedule(retry.event().eventId(), retry.attempt(),
                    retry.leaseOwner(), PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR,
                    "Transient webhook processing failure");
            if (!recorded) {
                logStaleClaim(retry);
                recordOutcome("stale_claim");
            } else {
                recordOutcome("transient_failure");
            }
        } catch (RuntimeException outcomeFailure) {
            recordOutcome("record_failure");
            log.error("Unable to record transient PayPal webhook failure for event {} (attempt {}, failure type {})",
                    retry.event().eventId(), retry.attempt(), outcomeFailure.getClass().getSimpleName());
        }
    }

    /**
     * Publishes how far past its own schedule the most overdue pending retry is.
     * An empty queue leaves the gauge at its last value only if a value ever existed;
     * it is deliberately not set to 0 so "unknown" is not reported as "healthy".
     */
    private void recordOldestPendingAge() {
        Double ageSeconds = retries.oldestPendingAgeSeconds();
        if (ageSeconds == null) return;
        Gauge.builder(OLDEST_PENDING_AGE_METRIC, ageSeconds, Double::doubleValue)
                .baseUnit("seconds")
                .register(meters);
    }

    private void recordOutcome(String outcome) {
        Counter.builder(OUTCOMES_METRIC).tag("outcome", outcome).register(meters).increment();
    }

    private void warnPollFailure(RuntimeException failure) {
        long now = System.nanoTime();
        long last = lastPollFailureLogNanos.get();
        if ((last == 0 || now - last >= FAILURE_LOG_INTERVAL_NANOS)
                && lastPollFailureLogNanos.compareAndSet(last, now)) {
            log.warn("PayPal webhook retry poll failed (failure type {})", failure.getClass().getSimpleName());
        }
    }
    private void logStaleClaim(PayPalWebhookRetryRepository.ClaimedRetry retry) {
        log.warn("PayPal webhook retry claim is no longer current for event {} (attempt {})",
                retry.event().eventId(), retry.attempt());
    }
}
