package com.atlas.application.service;

import com.atlas.payment.dto.response.PaymentWebhookResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 영속화된 검증 이벤트를 처리하며 provider API를 호출하지 않습니다. Issue #277. */
@Component
@ConditionalOnProperty(
        name = "atlas.paypal.webhook-retry.worker.enabled", havingValue = "true", matchIfMissing = true)
public class PayPalWebhookRetryWorker {
    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookRetryWorker.class);
    private final PayPalWebhookRetryRepository retries;
    private final PayPalWebhookService webhooks;

    public PayPalWebhookRetryWorker(PayPalWebhookRetryRepository retries, PayPalWebhookService webhooks) {
        this.retries = retries;
        this.webhooks = webhooks;
    }

    @Scheduled(initialDelayString = "${atlas.paypal.webhook-retry-initial-delay:PT1S}",
            fixedDelayString = "${atlas.paypal.webhook-retry-poll-interval:PT1S}")
    public void retryDueEvents() {
        List<PayPalWebhookRetryRepository.ClaimedRetry> claimed = retries.claimDue();
        for (PayPalWebhookRetryRepository.ClaimedRetry retry : claimed) {
                PaymentWebhookResult result;
                try {
                    result = webhooks.processVerified(retry.event());
                } catch (RuntimeException failure) {
                    recordTransientFailure(retry, failure);
                    continue;
                }

                try {
                    boolean recorded;
                    if (result.accepted() || result.duplicate()) {
                        recorded = retries.complete(retry.event().eventId(), retry.leaseOwner());
                    } else if (result.retryable()) {
                        recorded = retries.reschedule(retry.event().eventId(), retry.attempt(),
                                retry.leaseOwner(), PayPalWebhookRetryRepository.RetryFailure.CORRELATION_MISS,
                                "Verified event is not correlated yet");
                    } else {
                        recorded = retries.reject(retry.event().eventId(), retry.attempt(),
                                retry.leaseOwner(), "Verified event failed permanent validation");
                    }
                    if (!recorded) logStaleClaim(retry);
                } catch (RuntimeException failure) {
                    log.error("Unable to record PayPal webhook outcome for event {} (attempt {}, failure type {})",
                            retry.event().eventId(), retry.attempt(), failure.getClass().getSimpleName());
                }
        }
    }

    private void recordTransientFailure(PayPalWebhookRetryRepository.ClaimedRetry retry, RuntimeException failure) {
        log.warn("PayPal webhook retry failed for event {} (attempt {}, failure type {})",
                retry.event().eventId(), retry.attempt(), failure.getClass().getSimpleName());
        try {
            boolean recorded = retries.reschedule(retry.event().eventId(), retry.attempt(),
                    retry.leaseOwner(), PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR,
                    "Transient webhook processing failure");
            if (!recorded) logStaleClaim(retry);
        } catch (RuntimeException outcomeFailure) {
            log.error("Unable to record transient PayPal webhook failure for event {} (attempt {}, failure type {})",
                    retry.event().eventId(), retry.attempt(), outcomeFailure.getClass().getSimpleName());
        }
    }

    private void logStaleClaim(PayPalWebhookRetryRepository.ClaimedRetry retry) {
        log.warn("PayPal webhook retry claim is no longer current for event {} (attempt {})",
                retry.event().eventId(), retry.attempt());
    }
}
