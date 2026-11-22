package com.atlas.application.controller.seller;

import com.atlas.application.controller.dto.SellerPayoutProfileRequest;
import com.atlas.application.controller.dto.SellerPayoutProfileResponse;
import com.atlas.application.service.payout.SellerPayoutProfileService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PayPal 수취 profile은 Seller owner만 설정하며 email 주소 자체는 API에 받거나 반환하지 않는다. */
@RestController
@RequestMapping("/api/sellers/{sellerId}/payout-profile")
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class SellerPayoutProfileController {
    private final SellerPayoutProfileService profiles;

    public SellerPayoutProfileController(SellerPayoutProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public SellerPayoutProfileResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sellerId) {
        return profiles.find(UUID.fromString(jwt.getSubject()), sellerId);
    }

    @PutMapping
    @ResponseStatus(HttpStatus.OK)
    public SellerPayoutProfileResponse configure(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sellerId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SellerPayoutProfileRequest request) {
        return profiles.configure(UUID.fromString(jwt.getSubject()), sellerId, idempotencyKey, request);
    }
}
