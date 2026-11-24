package com.atlas.application.controller.settlement;

import com.atlas.application.dto.request.SettlementHttpRequest;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.application.service.payout.PayoutManualOperatorService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audited manual payout fallback, available only when automatic PayPal Payouts is disabled.
 * External transfer execution remains operator-owned and requires an evidence reference.
 */
@RestController
@RequestMapping("/api/internal/settlement/payouts")
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class SettlementPayoutOperatorController {
    private final PayoutManualOperatorService payouts;

    public SettlementPayoutOperatorController(PayoutManualOperatorService payouts) {
        this.payouts = payouts;
    }

    @PostMapping("/{payoutId}/approve")
    public PayoutResponse.SellerPayout approve(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request, Principal principal) {
        return payouts.approve(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }

    @PostMapping("/{payoutId}/sent")
    public PayoutResponse.SellerPayout markSent(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutManualEvidence request, Principal principal) {
        return payouts.markSent(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/confirm")
    public PayoutResponse.SellerPayout confirm(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutManualEvidence request, Principal principal) {
        return payouts.confirm(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/fail")
    public PayoutResponse.SellerPayout fail(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request, Principal principal) {
        return payouts.failBeforeDispatch(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }
}
