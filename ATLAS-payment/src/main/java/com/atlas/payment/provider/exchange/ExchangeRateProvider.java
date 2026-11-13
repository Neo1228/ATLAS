package com.atlas.payment.provider.exchange;

public interface ExchangeRateProvider {
    ExchangeRateQuote latestUsdKrwRate();
}
