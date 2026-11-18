package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.provider.PaymentWebhookEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PayPalWebhookRetryWorkerMetricsTest {
    private static final String RUNS_METRIC = "atlas.paypal.webhook.retry.runs";
    private static final String CLAIMED_METRIC = "atlas.paypal.webhook.retry.claimed";
    private static final String REAPED_METRIC = "atlas.paypal.webhook.retry.reaped";
    private static final String OUTCOMES_METRIC = "atlas.paypal.webhook.retry.outcomes";
    private static final String DURATION_METRIC = "atlas.paypal.webhook.retry.duration";
    private static final String ATTEMPT_DURATION_METRIC = "atlas.paypal.webhook.retry.attempt.duration";
    private static final String OLDEST_PENDING_AGE_METRIC = "atlas.paypal.webhook.retry.oldest_pending_age";

    private final PayPalWebhookRetryRepository retries = mock(PayPalWebhookRetryRepository.class);
    private final PayPalWebhookService webhooks = mock(PayPalWebhookService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PayPalWebhookRetryWorker worker = new PayPalWebhookRetryWorker(retries, webhooks, meters);

    @Test
    void recordsSuccessfulEmptyPollAsZeroClaims() {
        when(retries.pollDue()).thenReturn(new PayPalWebhookRetryRepository.PollResult(List.of(), 0));

        worker.retryDueEvents();

        assertThat(meters.counter(CLAIMED_METRIC).count()).isZero();
        assertThat(meters.find(REAPED_METRIC).counter()).isNull();
        assertThat(meters.counter(RUNS_METRIC, "outcome", "success").count()).isEqualTo(1);
        assertThat(meters.timer(DURATION_METRIC).count()).isEqualTo(1);
        assertThat(meters.timer(ATTEMPT_DURATION_METRIC).count()).isZero();
    }

    @Test
    void publishesOldestPendingRetryAgeAndStaysSilentWhenTheQueueIsEmpty() {
        when(retries.pollDue()).thenReturn(new PayPalWebhookRetryRepository.PollResult(List.of(), 0));
        when(retries.oldestPendingAgeSeconds()).thenReturn(42.5, (Double) null);

        worker.retryDueEvents();
        assertThat(meters.get(OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(42.5);

        worker.retryDueEvents();
        // An empty queue must not be reported as a healthy zero.
        assertThat(meters.get(OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(42.5);
    }

    @Test
    void recordsReapedTerminalRowsInOneTaglessSeries() {
        when(retries.pollDue()).thenReturn(new PayPalWebhookRetryRepository.PollResult(List.of(), 3),
                new PayPalWebhookRetryRepository.PollResult(List.of(), 2));

        worker.retryDueEvents();
        worker.retryDueEvents();

        assertThat(meters.counter(REAPED_METRIC).count()).isEqualTo(5);
        assertThat(meters.counter(REAPED_METRIC).getId().getTags()).isEmpty();
        assertThat(meters.find(REAPED_METRIC).meters()).hasSize(1);
        assertThat(meters.counter(CLAIMED_METRIC).count()).isZero();
    }

    @Test
    void recordsAcceptedDuplicateRescheduledAndRejectedOutcomes() {
        PaymentWebhookEvent accepted = event("accepted");
        PaymentWebhookEvent duplicate = event("duplicate");
        PaymentWebhookEvent rescheduled = event("rescheduled");
        PaymentWebhookEvent rejected = event("rejected");
        doReturn(
                new PayPalWebhookRetryRepository.PollResult(List.of(claim(accepted, "lease-1")), 0),
                new PayPalWebhookRetryRepository.PollResult(List.of(claim(duplicate, "lease-2")), 0),
                new PayPalWebhookRetryRepository.PollResult(List.of(claim(rescheduled, "lease-3")), 0),
                new PayPalWebhookRetryRepository.PollResult(
                        List.of(claim(rejected, "lease-4")), 0)).when(retries).pollDue();
        when(webhooks.processVerified(accepted)).thenReturn(result(false, true, false));
        when(webhooks.processVerified(duplicate)).thenReturn(result(true, true, false));
        when(webhooks.processVerified(rescheduled)).thenReturn(result(false, false, true));
        when(webhooks.processVerified(rejected)).thenReturn(result(false, false, false));
        when(retries.complete(accepted.eventId(), "lease-1")).thenReturn(true);
        when(retries.complete(duplicate.eventId(), "lease-2")).thenReturn(true);
        when(retries.reschedule(rescheduled.eventId(), 1, "lease-3",
                PayPalWebhookRetryRepository.RetryFailure.CORRELATION_MISS,
                "Verified event is not correlated yet")).thenReturn(true);
        when(retries.reject(rejected.eventId(), 1, "lease-4",
                "Verified event failed permanent validation")).thenReturn(true);

        worker.retryDueEvents();
        worker.retryDueEvents();
        worker.retryDueEvents();
        worker.retryDueEvents();

        assertThat(meters.counter(CLAIMED_METRIC).count()).isEqualTo(4);
        assertThat(outcome("accepted")).isEqualTo(1);
        assertThat(outcome("duplicate")).isEqualTo(1);
        assertThat(outcome("rescheduled")).isEqualTo(1);
        assertThat(outcome("rejected")).isEqualTo(1);
        assertThat(meters.counter(RUNS_METRIC, "outcome", "success").count()).isEqualTo(4);
        assertThat(meters.timer(ATTEMPT_DURATION_METRIC).count()).isEqualTo(4);
        assertThat(meters.find(OUTCOMES_METRIC).meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactly("outcome"));
    }

    @Test
    void recordsTransientFailureAndStaleLeaseOutcomes() {
        PaymentWebhookEvent transientFailure = event("transient");
        PaymentWebhookEvent staleLease = event("stale");
        doReturn(new PayPalWebhookRetryRepository.PollResult(List.of(claim(transientFailure, "lease-1")), 0),
                new PayPalWebhookRetryRepository.PollResult(List.of(claim(staleLease, "lease-2")), 0))
                .when(retries).pollDue();
        when(webhooks.processVerified(transientFailure)).thenThrow(new IllegalStateException("provider unavailable"));
        when(retries.reschedule(transientFailure.eventId(), 1, "lease-1",
                PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR,
                "Transient webhook processing failure")).thenReturn(true);
        when(webhooks.processVerified(staleLease)).thenReturn(result(false, true, false));
        when(retries.complete(staleLease.eventId(), "lease-2")).thenReturn(false);

        worker.retryDueEvents();
        worker.retryDueEvents();

        assertThat(outcome("transient_failure")).isEqualTo(1);
        assertThat(outcome("stale_claim")).isEqualTo(1);
        assertThat(meters.timer(DURATION_METRIC).count()).isEqualTo(2);
        assertThat(meters.timer(ATTEMPT_DURATION_METRIC).count()).isEqualTo(2);
    }

    @Test
    void recordsPollFailureAndReturnsForTheNextScheduledAttempt() {
        when(retries.pollDue()).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        worker.retryDueEvents();

        assertThat(meters.counter(RUNS_METRIC, "outcome", "failure").count()).isEqualTo(1);
        assertThat(meters.timer(DURATION_METRIC).count()).isEqualTo(1);
        assertThat(meters.timer(ATTEMPT_DURATION_METRIC).count()).isZero();
    }

    private double outcome(String outcome) {
        return meters.counter(OUTCOMES_METRIC, "outcome", outcome).count();
    }

    private static PayPalWebhookRetryRepository.ClaimedRetry claim(PaymentWebhookEvent event, String leaseOwner) {
        return new PayPalWebhookRetryRepository.ClaimedRetry(event, 1, leaseOwner);
    }

    private static PaymentWebhookEvent event(String id) {
        return new PaymentWebhookEvent(id, "PAYMENT.CAPTURE.COMPLETED", "resource-id", "COMPLETED",
                "provider-order-id", null, BigDecimal.ONE, "USD");
    }

    private static PaymentWebhookResult result(boolean duplicate, boolean accepted, boolean retryable) {
        return new PaymentWebhookResult(duplicate, accepted, null, null, null, false, retryable);
    }
}
