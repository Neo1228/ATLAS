package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.dto.response.ExchangeRateQuoteResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PayoutFxConversionServiceTest {
    private final PayoutFxConversionService conversions = new PayoutFxConversionService();

    @Test
    void roundsUsdCentsUpAndBalancesSellerPrincipalWithExplicitRoundingExpense() {
        var quote = new ExchangeRateQuoteResponse(new BigDecimal("1371.25"), "BOK_ECOS", LocalDate.parse("2026-10-31"));

        var result = conversions.convert(10_000, quote);

        assertThat(result.fxRateWonPerUsd()).isEqualByComparingTo("1371.25000000");
        assertThat(result.providerAmountMinor()).isEqualTo(730);
        assertThat(result.providerDebitBookValueWon()).isEqualTo(10_011);
        assertThat(result.fxRoundingExpenseWon()).isEqualTo(11);
        assertThat(result.providerDebitBookValueWon())
                .isEqualTo(10_000 + result.fxRoundingExpenseWon());
    }

    @Test
    void refusesPayoutsWhoseOneCentRoundingCostWouldExceedOnePercent() {
        var quote = new ExchangeRateQuoteResponse(new BigDecimal("1371.25"), "BOK_ECOS", LocalDate.parse("2026-10-31"));

        assertThatThrownBy(() -> conversions.convert(1, quote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one percent");
    }

    @Test
    void convertsProviderFeeUsingTheFrozenRateAndRoundsPlatformCostUp() {
        assertThat(conversions.usdAmountToWonCeiling(new BigDecimal("0.25"), new BigDecimal("1371.25")))
                .isEqualTo(343);
    }
}
