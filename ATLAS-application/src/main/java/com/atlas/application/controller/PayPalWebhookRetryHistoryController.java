package com.atlas.application.controller;

import com.atlas.application.dto.response.PayPalWebhookPendingRetryPage;
import com.atlas.application.dto.response.PayPalWebhookRetryAttemptHistoryResponse;
import com.atlas.application.dto.response.PayPalWebhookRetrySummary;
import com.atlas.application.service.PayPalWebhookRetryHistoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/payment/paypal/webhook-retries")
@ConditionalOnProperty(name = "atlas.payment.webhook-retry.operator.enabled", havingValue = "true")
public class PayPalWebhookRetryHistoryController {
    private final PayPalWebhookRetryHistoryService service;

    public PayPalWebhookRetryHistoryController(PayPalWebhookRetryHistoryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public PayPalWebhookRetrySummary summary() {
        return service.summary();
    }

    @GetMapping("/pending")
    public PayPalWebhookPendingRetryPage pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.pending(page, size);
    }

    @GetMapping("/{eventId}/history")
    public PayPalWebhookRetryAttemptHistoryResponse history(@PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.history(eventId, page, size);
    }
}
