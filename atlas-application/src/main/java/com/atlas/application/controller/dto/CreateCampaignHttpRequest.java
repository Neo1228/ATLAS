package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCampaignHttpRequest(
        @NotNull UUID sellerId,
        @NotBlank @Size(max = 128) String skuId,
        @Positive long queueTicketTtlSeconds) {
}
