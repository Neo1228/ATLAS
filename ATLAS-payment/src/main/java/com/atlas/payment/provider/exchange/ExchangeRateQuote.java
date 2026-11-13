package com.atlas.payment.provider.exchange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record ExchangeRateQuote(BigDecimal wonPerUsd, String source, LocalDate asOfDate) {
    public ExchangeRateQuote {
        Objects.requireNonNull(wonPerUsd, "wonPerUsd");
        Objects.requireNonNull(asOfDate, "asOfDate");
        if (wonPerUsd.signum() <= 0) throw new IllegalArgumentException("Exchange rate must be positive");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Exchange-rate source is required");
    }
}
