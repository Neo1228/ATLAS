package com.atlas.application.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Manual evidence is limited to provider facts; it never accepts recipient identity or a new payout amount. */
public record PayoutProviderResolutionRequest(
        @NotBlank @Pattern(regexp = "PAID|NOT_PAID") String outcome,
        @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9가-힣 .,_:/-]+") String evidenceReference,
        @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String providerBatchId,
        @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String providerItemId,
        @Min(1) Long providerAmountMinor,
        @Pattern(regexp = "USD") String providerCurrency,
        @Min(0) Long providerFeeMinor) {
    @AssertTrue
    public boolean isValid() {
        if ("PAID".equals(outcome)) {
            return providerBatchId != null && providerItemId != null && providerAmountMinor != null
                    && "USD".equals(providerCurrency) && providerFeeMinor != null;
        }
        return "NOT_PAID".equals(outcome) && providerAmountMinor == null
                && "USD".equals(providerCurrency) && Long.valueOf(0).equals(providerFeeMinor);
    }
}
