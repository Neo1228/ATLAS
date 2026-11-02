package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateCampaignHttpRequest(
        @NotBlank @Size(max = 128) String skuId,
        @Positive int inventoryCapacity,
        @Positive long queueTicketTtlSeconds,
        @Positive int maxActiveCheckouts) {
}
