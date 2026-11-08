package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record SetSkuSalePriceHttpRequest(
        @Positive long salePriceWon,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt) {
}
