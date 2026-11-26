package com.atlas.payment.provider.exchange;

import com.atlas.payment.dto.response.FxResponse;
import com.atlas.payment.exception.PaymentException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 가입·API key 없이 쓸 수 있는 Frankfurter(ECB 기준환율) USD/KRW 환율.
 *
 * <p>로컬·Sandbox 실행용 대안이다. ECB는 영업일 기준으로 하루 한 번 고시하므로 주말·공휴일에는
 * 직전 영업일 값이 반환된다. 기본 공급자는 BOK ECOS이며 {@code atlas.payment.exchange-rate.provider=frankfurter}
 * 일 때만 사용한다. 출처와 기준일은 결제 시도의 FX snapshot에 함께 저장된다.
 */
@Component
@Qualifier("exchangeRateProviderDelegate")
@ConditionalOnProperty(name = "atlas.payment.exchange-rate.provider", havingValue = "frankfurter")
public class FrankfurterExchangeRateProvider implements ExchangeRateProvider {
    private static final String SOURCE = "FRANKFURTER_ECB_USD_KRW";
    private static final int MAX_RATE_AGE_DAYS = 7;

    private final RestClient client;

    public FrankfurterExchangeRateProvider(RestClient.Builder builder,
            @Value("${atlas.payment.frankfurter.base-url:https://api.frankfurter.dev}") String baseUrl) {
        Objects.requireNonNull(builder);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public FxResponse.ExchangeRateQuote latestUsdKrwRate() {
        try {
            JsonNode response = client.get()
                    .uri("/v1/latest?base=USD&symbols=KRW")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) throw unavailable();
            LocalDate asOfDate = LocalDate.parse(response.path("date").asString());
            BigDecimal wonPerUsd = new BigDecimal(response.path("rates").path("KRW").asString());
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (wonPerUsd.signum() <= 0 || asOfDate.isAfter(today.plusDays(1))
                    || ChronoUnit.DAYS.between(asOfDate, today) > MAX_RATE_AGE_DAYS) {
                throw unavailable();
            }
            return new FxResponse.ExchangeRateQuote(wonPerUsd, SOURCE, asOfDate);
        } catch (RuntimeException exception) {
            if (exception instanceof PaymentException paymentException) throw paymentException;
            throw unavailable();
        }
    }

    private static PaymentException unavailable() {
        return new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                "Frankfurter exchange-rate data is unavailable");
    }
}
