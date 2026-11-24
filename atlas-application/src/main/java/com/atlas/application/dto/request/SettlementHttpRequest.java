package com.atlas.application.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SettlementHttpRequest {
    private SettlementHttpRequest() {
    }

    /** External bank/provider reference only; never include recipient email or authentication data. */
    public record PayoutManualEvidence(
            @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9가-힣 .,_:/-]+") String evidenceReference) {
    }

    /** Audit reason only; never include credentials or recipient email. */
    public record PayoutOperatorAction(
            @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9가-힣 .,/_:-]+") String reason) {
    }

    /** Manual evidence is limited to provider facts; it never accepts recipient identity or a new payout amount. */
    public record PayoutProviderResolution(
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

    /** Seller confirms the linked verified account email as the PayPal recipient. */
    public record SellerPayoutProfile(
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @AssertTrue boolean usdWalletConfirmed,
            @AssertTrue boolean verifiedAccountEmailConfirmed) {
    }

    public record VerifySellerPayoutProfile(
            @Min(1) int profileVersion,
            @NotBlank @Size(max = 500) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,499}") String evidenceReference) {
    }

    /** 대사 행에 기록할 운영자 disposition 요청입니다. */
    public record PayPalBalanceReportDisposition(
            @NotBlank @Size(max = 32) String disposition,
            @NotBlank @Size(max = 500) String reason) {
    }
}
