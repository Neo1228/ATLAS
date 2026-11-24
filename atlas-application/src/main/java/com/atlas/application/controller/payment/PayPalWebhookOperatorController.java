package com.atlas.application.controller.payment;

import com.atlas.application.dto.request.PaymentHttpRequest;
import com.atlas.payment.dto.response.WebhookRetryResponse;
import com.atlas.payment.service.PayPalWebhookOperatorService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/payment/paypal/webhook-retries")
@ConditionalOnProperty(name = "atlas.payment.webhook-retry.operator.enabled", havingValue = "true")
public class PayPalWebhookOperatorController {
    private final PayPalWebhookOperatorService service;

    public PayPalWebhookOperatorController(PayPalWebhookOperatorService service) {
        this.service = service;
    }

    @GetMapping("/exhausted")
    public WebhookRetryResponse.ExhaustedRetryPage exhausted(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listExhausted(page, size);
    }

    @PostMapping("/{eventId}/requeue")
    public ResponseEntity<WebhookRetryResponse.Requeue> requeue(
            @PathVariable String eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentHttpRequest.WebhookRetryRequeue request,
            Principal principal) {
        WebhookRetryResponse.Requeue response = service.requeue(
                eventId, idempotencyKey, principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

}
