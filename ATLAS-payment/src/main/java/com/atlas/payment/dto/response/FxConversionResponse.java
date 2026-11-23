package com.atlas.payment.dto.response;

import com.atlas.payment.dto.response.ExchangeRateQuoteResponse;

public record FxConversionResponse(ExchangeRateQuoteResponse quote, long usdCents) { }
