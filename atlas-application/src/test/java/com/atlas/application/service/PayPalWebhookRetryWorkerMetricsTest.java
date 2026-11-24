package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.ClaimedWebhookRetryResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.dto.response.WebhookRetryPollResponse;
import com.atlas.payment.model.WebhookRetryFailure;
import com.atlas.payment.service.PayPalWebhookRetryService;
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

    private final PayPalWebhookRetryService retries = mock(PayPalWebhookRetryService.class);
    private final PayPalWebhookService webhooks = mock(PayPalWebhookService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PayPalWebhookRetryWorker worker = new PayPalWebhookRetryWorker(retries, webhooks, meters);

    @Test
    void recordsSuccessfulEmptyPollAsZeroClaims() {
        when(retries.pollDue()).thenReturn(new WebhookRetryPollResponse(List.of(), 0));

        worker.retryDueEvents();

        assertThat(meters.counter(CLAIMED_METRIC).count()).isZero();
        assertThat(meters.find(REAPED_METRIC).counter()).isNull();
        assertThat(meters.counter(RUNS_METRIC, "outcome", "success").count()).isEqualTo(1);
        assertThat(meters.timer(DURATION_METRIC).count()).isEqualTo(1);
        assertThat(meters.timer(ATTEMPT_DURATION_METRIC).count()).isZero();
    }

    @Test
    void publishesOldestPendingRetryAgeAndStaysSilentWhenTheQueueIsEmpty() {
        when(retries.pollDue()).thenReturn(new WebhookRetryPollResponse(List.of(), 0));
        when(retries.oldestPendingAgeSeconds()).thenReturn(42.5, (Double) null);

        worker.retryDueEvents();
        assertThat(meters.get(OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(42.5);

        worker.retryDueEvents();
        // 빈 큐를 healthy 0으로 보고하면 안 된다.
        assertThat(meters.get(OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(42.5);
    }

    @Test
    void recordsReapedTerminalRowsInOneTaglessSeries() {
        when(retries.pollDue()).thenReturn(new WebhookRetryPollResponse(List.of(), 3),
                new WebhookRetryPollResponse(List.of(), 2));

        worker.retryDueEvents();
        worker.retryDueEvents();

        assertThat(meters.counter(REAPED_METRIC).count()).isEqualTo(5);
        assertThat(meters.counter(REAPED_METRIC).getId().getTags()).isEmpty();
        assertThat(meters.find(REAPED_METRIC).meters()).hasSize(1);
        assertThat(meters.counter(CLAIMED_METRIC).count()).isZero();
    }

    @Test
    void recordsAcceptedDuplicateRescheduledAndRejectedOutcomes() {
        PaymentWebhookEventResponse accepted = event("accepted");
        PaymentWebhookEventResponse duplicate = event("duplicate");
        PaymentWebhookEventResponse rescheduled = event("rescheduled");
        PaymentWebhookEventResponse rejected = event("rejected");
        doReturn(
                new WebhookRetryPollResponse(List.of(claim(accepted, "lease-1")), 0),
                new WebhookRetryPollResponse(List.of(claim(duplicate, "lease-2")), 0),
                new WebhookRetryPollResponse(List.of(claim(rescheduled, "lease-3")), 0),
                new WebhookRetryPollResponse(
                        List.of(claim(rejected, "lease-4")), 0)).when(retries).pollDue();
        when(webhooks.processVerified(accepted)).thenReturn(result(false, true, false));
        when(webhooks.processVerified(duplicate)).thenReturn(result(true, true, false));
        when(webhooks.processVerified(rescheduled)).thenReturn(result(false, false, true));
        when(webhooks.processVerified(rejected)).thenReturn(result(false, false, false));
        when(retries.complete(accepted.eventId(), "lease-1")).thenReturn(true);
        when(retries.complete(duplicate.eventId(), "lease-2")).thenReturn(true);
        when(retries.reschedule(rescheduled.eventId(), 1, "lease-3",
                WebhookRetryFailure.CORRELATION_MISS,
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
        PaymentWebhookEventResponse transientFailure = event("transient");
        PaymentWebhookEventResponse staleLease = event("stale");
        doReturn(new WebhookRetryPollResponse(List.of(claim(transientFailure, "lease-1")), 0),
                new WebhookRetryPollResponse(List.of(claim(staleLease, "lease-2")), 0))
                .when(retries).pollDue();
        when(webhooks.processVerified(transientFailure)).thenThrow(new IllegalStateException("provider unavailable"));
        when(retries.reschedule(transientFailure.eventId(), 1, "lease-1",
                WebhookRetryFailure.TRANSIENT_ERROR,
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

    private static ClaimedWebhookRetryResponse claim(PaymentWebhookEventResponse event, String leaseOwner) {
        return new ClaimedWebhookRetryResponse(event, 1, leaseOwner);
    }

    private static PaymentWebhookEventResponse event(String id) {
        return new PaymentWebhookEventResponse(id, "PAYMENT.CAPTURE.COMPLETED", "resource-id", "COMPLETED",
                "provider-order-id", null, BigDecimal.ONE, "USD");
    }

    private static PaymentWebhookResponse result(boolean duplicate, boolean accepted, boolean retryable) {
        return new PaymentWebhookResponse(duplicate, accepted, null, null, null, false, retryable);
    }

}
