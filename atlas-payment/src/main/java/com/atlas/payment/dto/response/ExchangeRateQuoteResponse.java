package com.atlas.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record ExchangeRateQuoteResponse(BigDecimal wonPerUsd, String source, LocalDate asOfDate) {
    public ExchangeRateQuoteResponse {
        Objects.requireNonNull(wonPerUsd, "wonPerUsd");
        Objects.requireNonNull(asOfDate, "asOfDate");
        if (wonPerUsd.signum() <= 0) throw new IllegalArgumentException("Exchange rate must be positive");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Exchange-rate source is required");
    }
}
