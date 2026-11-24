package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record CreateSellerCouponRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{3,32}")
        String code,
        @Positive long discountAmountWon,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Positive Long maxRedemptions) {
}
