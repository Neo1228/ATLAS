package com.atlas.payment.service.impl;

import com.atlas.payment.dto.response.ClaimedWebhookRetryResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.response.WebhookRetryPollResponse;
import com.atlas.payment.persistence.projection.ClaimedWebhookRetry;
import com.atlas.payment.persistence.projection.WebhookRetryPollResult;
import com.atlas.payment.persistence.projection.WebhookRetryEvent;
import com.atlas.payment.persistence.state.WebhookRetryFailure;
import com.atlas.payment.repository.PayPalWebhookRetryRepository;
import com.atlas.payment.service.PayPalWebhookRetryService;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PayPalWebhookRetryServiceImpl implements PayPalWebhookRetryService {
    private final PayPalWebhookRetryRepository retries;

    public PayPalWebhookRetryServiceImpl(PayPalWebhookRetryRepository retries) {
        this.retries = Objects.requireNonNull(retries, "retries");
    }

    @Override
    public void enqueue(PaymentWebhookEventResponse event) {
        retries.enqueue(new WebhookRetryEvent(event.eventId(), event.eventType(), event.resourceId(),
                event.resourceStatus(), event.providerOrderId(), event.relatedCaptureId(), event.resourceAmount(),
                event.resourceCurrency()));
    }

    @Override
    public WebhookRetryPollResponse pollDue() {
        WebhookRetryPollResult poll = retries.pollDue();
        return new WebhookRetryPollResponse(poll.claimed().stream().map(PayPalWebhookRetryServiceImpl::toResponse).toList(),
                poll.reapedCount());
    }

    @Override
    public boolean complete(String eventId, String leaseOwner) {
        return retries.complete(eventId, leaseOwner);
    }

    @Override
    public boolean reschedule(String eventId, int attempt, String leaseOwner, WebhookRetryFailure failure,
            String reason) {
        return retries.reschedule(eventId, attempt, leaseOwner, failure, reason);
    }

    @Override
    public boolean reject(String eventId, int attempt, String leaseOwner, String reason) {
        return retries.reject(eventId, attempt, leaseOwner, reason);
    }

    @Override
    public Double oldestPendingAgeSeconds() {
        return retries.oldestPendingAgeSeconds();
    }

    private static ClaimedWebhookRetryResponse toResponse(ClaimedWebhookRetry claimed) {
        WebhookRetryEvent e = claimed.event();
        return new ClaimedWebhookRetryResponse(new PaymentWebhookEventResponse(e.eventId(), e.eventType(),
                e.resourceId(), e.resourceStatus(), e.providerOrderId(), e.relatedCaptureId(), e.resourceAmount(),
                e.resourceCurrency()), claimed.attempt(), claimed.leaseOwner());
    }
}
