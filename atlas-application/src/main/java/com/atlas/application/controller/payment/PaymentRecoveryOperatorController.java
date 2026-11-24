package com.atlas.application.controller.payment;

import com.atlas.application.dto.request.PaymentHttpRequest;
import com.atlas.application.dto.response.PaymentHttpResponse;
import com.atlas.application.service.PaymentRecoveryOperatorService;
import com.atlas.payment.dto.response.PaymentRecoveryResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
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
@RequestMapping("/api/internal/payment/recovery")
@ConditionalOnProperty(name = "atlas.payment.recovery.operator.enabled", havingValue = "true")
public class PaymentRecoveryOperatorController {
    private final PaymentRecoveryOperatorService service;

    public PaymentRecoveryOperatorController(PaymentRecoveryOperatorService service) {
        this.service = service;
    }

    @GetMapping("/needs-review")
    public PaymentHttpResponse.RecoveryReviewPage needsReview(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.listNeedsReview(limit, offset);
    }

    @PostMapping("/{recoveryJobId}/requeue")
    public ResponseEntity<PaymentRecoveryResponse.Requeue> requeue(
            @PathVariable UUID recoveryJobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentHttpRequest.RecoveryRequeue request,
            Principal principal) {
        PaymentRecoveryResponse.Requeue response = service.requeue(
                recoveryJobId, idempotencyKey, principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
