package com.atlas.payment.provider.exchange;

import com.atlas.payment.dto.response.FxResponse;

public interface ExchangeRateProvider {
    FxResponse.ExchangeRateQuote latestUsdKrwRate();
}
