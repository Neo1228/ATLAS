package com.atlas.application.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Seller confirms the linked verified account email as the PayPal recipient. */
public record SellerPayoutProfileRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
        @AssertTrue boolean usdWalletConfirmed,
        @AssertTrue boolean verifiedAccountEmailConfirmed) {
}