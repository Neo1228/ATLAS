package com.atlas.application.service;

import com.atlas.payment.dto.response.PaymentWebhookResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 永続化済み検証イベントを処理する。provider APIは呼び出さない。Issue #277。 */
@Component
public class PayPalWebhookRetryWorker {
    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookRetryWorker.class);
    private final PayPalWebhookRetryRepository retries;
    private final PayPalWebhookService webhooks;

    public PayPalWebhookRetryWorker(PayPalWebhookRetryRepository retries, PayPalWebhookService webhooks) {
        this.retries = retries;
        this.webhooks = webhooks;
    }

    @Scheduled(fixedDelayString = "${atlas.paypal.webhook-retry-poll-interval:PT1S}")
    public void retryDueEvents() {
        List<PayPalWebhookRetryRepository.ClaimedRetry> claimed = retries.claimDue();
        for (PayPalWebhookRetryRepository.ClaimedRetry retry : claimed) {
            try {
                PaymentWebhookResult result = webhooks.processVerified(retry.event());
                if (result.retryable()) {
                    retries.reschedule(retry.event().eventId(), retry.attempt(), retry.leaseOwner(),
                            "PayPal event is still not correlated");
                } else {
                    // 永続的な不一致・成功・重複イベントは終端としてキューから削除する。
                    retries.complete(retry.event().eventId(), retry.leaseOwner());
                }
            } catch (RuntimeException failure) {
                log.warn("PayPal webhook retry failed for event {} (attempt {})",
                        retry.event().eventId(), retry.attempt(), failure);
                retries.reschedule(retry.event().eventId(), retry.attempt(), retry.leaseOwner(), failure.toString());
            }
        }
    }
}
