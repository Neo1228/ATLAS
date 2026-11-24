package com.atlas.payment.provider.exchange;

import com.atlas.payment.dto.response.ExchangeRateQuoteResponse;

public interface ExchangeRateProvider {
    ExchangeRateQuoteResponse latestUsdKrwRate();
}
