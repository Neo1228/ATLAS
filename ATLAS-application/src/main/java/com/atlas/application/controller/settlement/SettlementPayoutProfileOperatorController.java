package com.atlas.application.controller.settlement;

import com.atlas.application.controller.dto.SellerPayoutProfileResponse;
import com.atlas.application.controller.dto.VerifySellerPayoutProfileRequest;
import com.atlas.application.service.payout.SellerPayoutProfileService;
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

/** Operator review gate for linking verified Seller identity to PayPal payout eligibility. */
@RestController
@RequestMapping("/api/internal/settlement/payout-profiles")
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class SettlementPayoutProfileOperatorController {
    private final SellerPayoutProfileService profiles;

    public SettlementPayoutProfileOperatorController(SellerPayoutProfileService profiles) {
        this.profiles = profiles;
    }

    @PostMapping("/{sellerId}/verify")
    public SellerPayoutProfileResponse verify(@PathVariable UUID sellerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VerifySellerPayoutProfileRequest request,
            Principal principal) {
        return profiles.verify(sellerId, principal == null ? null : principal.getName(), idempotencyKey, request);
    }
}
