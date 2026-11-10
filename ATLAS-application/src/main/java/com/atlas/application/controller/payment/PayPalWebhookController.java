package com.atlas.application.controller.payment;

import com.atlas.application.service.PayPalWebhookService;
import com.atlas.payment.provider.PaymentWebhookSignature;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/paypal")
public class PayPalWebhookController {
    private final PayPalWebhookService webhooks;

    public PayPalWebhookController(PayPalWebhookService webhooks) {
        this.webhooks = Objects.requireNonNull(webhooks);
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader("paypal-auth-algo") String authAlgorithm,
            @RequestHeader("paypal-cert-url") String certificateUrl,
            @RequestHeader("paypal-transmission-id") String transmissionId,
            @RequestHeader("paypal-transmission-sig") String transmissionSignature,
            @RequestHeader("paypal-transmission-time") String transmissionTime,
            @RequestBody String rawBody) {
        webhooks.handle(new PaymentWebhookSignature(authAlgorithm, certificateUrl, transmissionId,
                transmissionSignature, transmissionTime), rawBody);
        return ResponseEntity.noContent().build();
    }
}
