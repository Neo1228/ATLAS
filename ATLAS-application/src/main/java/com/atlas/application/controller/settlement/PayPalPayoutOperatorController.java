package com.atlas.application.controller.settlement;

import com.atlas.application.controller.dto.PayoutDispatchStatusResponse;
import com.atlas.application.controller.dto.PayoutDispatchReviewPage;
import com.atlas.application.controller.dto.PayoutManualEvidenceRequest;
import com.atlas.application.controller.dto.PayoutProviderResolutionRequest;
import com.atlas.application.controller.dto.PayoutOperatorActionRequest;
import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.application.service.payout.PayoutManualOperatorService;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public ResponseEntity<SellerPayoutResponse> approve(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutOperatorActionRequest request,
            Principal principal) {
        SellerPayoutResponse response = approvals.approve(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{payoutId}/cancel-before-dispatch")
    public SellerPayoutResponse cancelBeforeDispatch(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutOperatorActionRequest request,
            Principal principal) {
        return approvals.cancelBeforeDispatch(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }

    @GetMapping("/{payoutId}/dispatch")
    public PayoutDispatchStatusResponse dispatchStatus(@PathVariable UUID payoutId) {
        return approvals.dispatchStatus(payoutId);
    }

    @GetMapping("/needs-review")
    public PayoutDispatchReviewPage needsReview(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return approvals.needsReview(limit, offset);
    }

    @PostMapping("/{payoutId}/requeue")
    public SellerPayoutResponse requeueNeedsReview(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutOperatorActionRequest request, Principal principal) {
        return approvals.requeueNeedsReview(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }


    @PostMapping("/{payoutId}/resolve-provider-review")
    public SellerPayoutResponse resolveProviderReview(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutProviderResolutionRequest request, Principal principal) {
        return manualPayouts.resolveProviderReview(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request);
    }
    /** Audited KRW/manual fallback for countries and recipients not enabled for automatic PayPal Payouts. */
    @PostMapping("/{payoutId}/manual-approve")
    public ResponseEntity<SellerPayoutResponse> approveManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutOperatorActionRequest request, Principal principal) {
        SellerPayoutResponse response = manualPayouts.approve(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{payoutId}/manual-sent")
    public SellerPayoutResponse markManualSent(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutManualEvidenceRequest request, Principal principal) {
        return manualPayouts.markSent(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/manual-confirm")
    public SellerPayoutResponse confirmManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutManualEvidenceRequest request, Principal principal) {
        return manualPayouts.confirm(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }

    @PostMapping("/{payoutId}/manual-fail")
    public SellerPayoutResponse failManual(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutOperatorActionRequest request, Principal principal) {
        return manualPayouts.failBeforeDispatch(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.reason());
    }

    @PostMapping("/{payoutId}/manual-no-transfer")
    public SellerPayoutResponse reconcileLegacyNoTransfer(@PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoutManualEvidenceRequest request, Principal principal) {
        return manualPayouts.reconcileNoTransfer(payoutId, idempotencyKey,
                principal == null ? null : principal.getName(), request.evidenceReference());
    }
}
