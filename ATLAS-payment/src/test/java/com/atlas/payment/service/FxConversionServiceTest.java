package com.atlas.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FxConversionServiceTest {
    @Test
    void convertsKrwToUsdCentsUsingHalfUpAndReturnsTheRateSnapshot() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                new BigDecimal("1332.4"), "BOK_ECOS_731Y001_0000001", LocalDate.of(2026, 11, 13));
        FxConversionService service = new FxConversionService(() -> quote);

        FxConversionService.Conversion conversion = service.convertKrwToUsdCents(1_000);

        assertThat(conversion.usdCents()).isEqualTo(75);
        assertThat(conversion.quote()).isSameAs(quote);
        assertThat(FxConversionService.krwToUsdCents(1_000, new BigDecimal("1500"))).isEqualTo(67);
        assertThat(FxConversionService.krwToUsdCents(1, new BigDecimal("200"))).isEqualTo(1);
    }

    @Test
    void rejectsKrwAmountsThatRoundBelowOneUsdCent() {
        assertThatThrownBy(() -> FxConversionService.krwToUsdCents(1, new BigDecimal("201")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD 0.01");
    }
}
