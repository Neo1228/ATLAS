package com.atlas.payment.service;

import com.atlas.payment.provider.exchange.ExchangeRateProvider;
import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class FxConversionService {
    private final ExchangeRateProvider exchangeRates;

    public FxConversionService(ExchangeRateProvider exchangeRates) {
        this.exchangeRates = Objects.requireNonNull(exchangeRates);
    }

    public Conversion convertKrwToUsdCents(long amountKrw) {
        if (amountKrw <= 0) throw new IllegalArgumentException("A positive KRW amount is required");
        ExchangeRateQuote quote = Objects.requireNonNull(exchangeRates.latestUsdKrwRate());
        return new Conversion(quote, krwToUsdCents(amountKrw, quote.wonPerUsd()));
    }

    public static long krwToUsdCents(long amountKrw, BigDecimal wonPerUsd) {
        if (amountKrw <= 0 || wonPerUsd == null || wonPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("Positive amount and FX rate are required");
        }
        long cents = BigDecimal.valueOf(amountKrw).divide(wonPerUsd, 2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact();
        if (cents <= 0) throw new IllegalArgumentException("Converted amount is below USD 0.01");
        return cents;
    }

    public record Conversion(ExchangeRateQuote quote, long usdCents) { }
}
