package com.atlas.payment.service;

import com.atlas.payment.exception.PaymentException;
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
        ExchangeRateQuote quote = exchangeRates.latestUsdKrwRate();
        // 환율 조회 실패는 결제 대행사 일시 장애와 같은 계약(503 + 재시도 안내)으로 드러낸다.
        // provider 가 계약을 어기고 null 을 돌려줘도 NPE(500)로 새지 않게 이 경계에서 막는다.
        if (quote == null) {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                    "Exchange-rate quote is unavailable");
        }
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
