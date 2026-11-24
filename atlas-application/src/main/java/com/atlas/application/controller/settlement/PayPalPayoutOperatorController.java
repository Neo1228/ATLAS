package com.atlas.application.controller.settlement;

import com.atlas.application.dto.response.SettlementHttpResponse;
import com.atlas.application.dto.request.SettlementHttpRequest;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.application.service.payout.PayoutManualOperatorService;
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

/** Sandbox PayPal Payouts approval API. Only this route can create a durable provider intent. */
@RestController
@RequestMapping("/api/internal/settlement/payouts")
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayPalPayoutOperatorController {
    private final PayoutDispatchApprovalService approvals;
    private final PayoutManualOperatorService manualPayouts;

    public PayPalPayoutOperatorController(PayoutDispatchApprovalService approvals,
            PayoutManualOperatorService manualPayouts) {
        this.approvals = approvals;
        this.manualPayouts = manualPayouts;
    }

    @PostMapping("/{payoutId}/approve")
    public ResponseEntity<PayoutResponse.SellerPayout> approve(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request,
            Principal principal) {
        PayoutResponse.SellerPayout response = approvals.approve(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{payoutId}/cancel-before-dispatch")
    public PayoutResponse.SellerPayout cancelBeforeDispatch(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request,
            Principal principal) {
        return approvals.cancelBeforeDispatch(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }

    @GetMapping("/{payoutId}/dispatch")
    public SettlementHttpResponse.PayoutDispatchStatus dispatchStatus(@PathVariable UUID payoutId) {
        return approvals.dispatchStatus(payoutId);
    }

    @GetMapping("/needs-review")
    public SettlementHttpResponse.PayoutDispatchReviewPage needsReview(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return approvals.needsReview(limit, offset);
    }

    @PostMapping("/{payoutId}/requeue")
    public PayoutResponse.SellerPayout requeueNeedsReview(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request, Principal principal) {
        return approvals.requeueNeedsReview(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }


    @PostMapping("/{payoutId}/resolve-provider-review")
    public PayoutResponse.SellerPayout resolveProviderReview(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutProviderResolution request, Principal principal) {
        return manualPayouts.resolveProviderReview(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request);
    }
    /** Audited KRW/manual fallback for countries and recipients not enabled for automatic PayPal Payouts. */
    @PostMapping("/{payoutId}/manual-approve")
    public ResponseEntity<PayoutResponse.SellerPayout> approveManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request, Principal principal) {
        PayoutResponse.SellerPayout response = manualPayouts.approve(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{payoutId}/manual-sent")
    public PayoutResponse.SellerPayout markManualSent(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutManualEvidence request, Principal principal) {
        return manualPayouts.markSent(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/manual-confirm")
    public PayoutResponse.SellerPayout confirmManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutManualEvidence request, Principal principal) {
        return manualPayouts.confirm(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/manual-fail")
    public PayoutResponse.SellerPayout failManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutOperatorAction request, Principal principal) {
        return manualPayouts.failBeforeDispatch(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }

    @PostMapping("/{payoutId}/manual-no-transfer")
    public PayoutResponse.SellerPayout reconcileLegacyNoTransfer(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SettlementHttpRequest.PayoutManualEvidence request, Principal principal) {
        return manualPayouts.reconcileNoTransfer(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }
}
