package com.atlas.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AdmissionHttpRequest {
    private AdmissionHttpRequest() {
    }

    public record ConfigureSellerSkuPolicy(@Positive int maxActiveCheckouts) {
    }

    public record CreateCampaign(
            @NotNull UUID sellerId,
            @NotBlank @Size(max = 128) String skuId,
            @Positive long queueTicketTtlSeconds) {
    }

    public record JoinQueue(
            @NotBlank @Size(max = 128) String skuId,
            @NotBlank @Size(max = 256) String buyerRef) {
    }
}
