package com.atlas.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AdmissionHttpRequest {
    private AdmissionHttpRequest() {
    }

    /** {@code maxActiveCheckouts}를 생략하면 {@code atlas.admission.default-max-active-checkouts}를 쓴다. */
    public record ConfigureSellerSkuPolicy(@Positive Integer maxActiveCheckouts) {
    }

    /** {@code queueTicketTtlSeconds}를 생략하면 {@code atlas.admission.default-ticket-ttl}을 쓴다. */
    public record CreateCampaign(
            @NotNull UUID sellerId,
            @NotBlank @Size(max = 128) String skuId,
            @Positive Long queueTicketTtlSeconds) {
    }

    public record JoinQueue(
            @NotBlank @Size(max = 128) String skuId,
            @NotBlank @Size(max = 256) String buyerRef) {
    }
}
