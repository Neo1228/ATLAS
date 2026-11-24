package com.atlas.application.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** Seller payout eligibility profile; recipient email is intentionally never returned. */
public record SellerPayoutProfileResponse(
        UUID sellerId,
        String countryCode,
        String payoutCurrency,
        String status,
        int profileVersion,
        Instant updatedAt,
        Instant verifiedAt) {
}