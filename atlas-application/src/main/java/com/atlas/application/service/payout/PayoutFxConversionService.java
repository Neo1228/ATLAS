package com.atlas.application.service.payout;

import com.atlas.payment.dto.response.FxResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Converts Seller KRW payable to USD cents without rounding the Seller's payable downward. */
@Component
public class PayoutFxConversionService {
    private static final int RATE_SCALE = 8;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_ROUNDING_RATIO = new BigDecimal("0.01");

    public Conversion convert(long amountWon, FxResponse.ExchangeRateQuote quote) {
        if (amountWon <= 0) throw new IllegalArgumentException("A positive Seller payable is required");
        Objects.requireNonNull(quote, "quote");
        BigDecimal rate = quote.wonPerUsd().setScale(RATE_SCALE, RoundingMode.HALF_UP);
        if (rate.signum() <= 0 || !quote.source().matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("FX quote cannot be stored by the payout ledger");
        }
        long usdCents = BigDecimal.valueOf(amountWon).multiply(ONE_HUNDRED)
                .divide(rate, 0, RoundingMode.CEILING).longValueExact();
        if (usdCents <= 0) throw new IllegalArgumentException("Converted amount is below USD 0.01");
        long debitBookValueWon = usdCentsToWonCeiling(usdCents, rate);
        long roundingExpenseWon = Math.subtractExact(debitBookValueWon, amountWon);
        if (roundingExpenseWon < 0) {
            throw new IllegalStateException("Payout conversion would underpay Seller payable");
        }
        long maxRoundingExpenseWon = BigDecimal.valueOf(amountWon).multiply(MAX_ROUNDING_RATIO)
                .setScale(0, RoundingMode.CEILING).longValueExact();
        if (roundingExpenseWon > maxRoundingExpenseWon) {
            throw new IllegalArgumentException("Payout FX cent rounding exceeds one percent of Seller payable");
        }
        return new Conversion(rate, quote.source(), quote.asOfDate(), usdCents,
                debitBookValueWon, roundingExpenseWon);
    }

    public long usdCentsToWonCeiling(long usdCents, BigDecimal wonPerUsd) {
        if (usdCents < 0 || wonPerUsd == null || wonPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("A non-negative USD amount and positive FX rate are required");
        }
        return BigDecimal.valueOf(usdCents).multiply(wonPerUsd)
                .divide(ONE_HUNDRED, 0, RoundingMode.CEILING).longValueExact();
    }

    public long usdAmountToWonCeiling(BigDecimal usdAmount, BigDecimal wonPerUsd) {
        if (usdAmount == null || usdAmount.signum() < 0 || wonPerUsd == null || wonPerUsd.signum() <= 0) {
            throw new IllegalArgumentException("A non-negative USD fee and positive FX rate are required");
        }
        return usdAmount.multiply(wonPerUsd).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    public record Conversion(BigDecimal fxRateWonPerUsd, String source, LocalDate asOfDate,
            long providerAmountMinor, long providerDebitBookValueWon, long fxRoundingExpenseWon) { }
}
