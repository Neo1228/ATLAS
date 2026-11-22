package com.atlas.application.service.payout;

import java.time.Instant;
import java.util.UUID;

/** Non-secret projection of the recipient account and provider eligibility snapshot. */
public record SellerPayoutProfile(
        UUID sellerId,
        UUID recipientAccountId,
        String countryCode,
        String payoutCurrency,
        State state,
        int profileVersion,
        Instant updatedAt,
        Instant verifiedAt,
        String verifiedBy) {
    public enum State {
        PENDING_REVIEW,
        ACTIVE,
        SUSPENDED
    }
}
