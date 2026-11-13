package com.atlas.payment.provider.exchange;

import com.atlas.payment.exception.PaymentException;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BokEcosExchangeRateProvider implements ExchangeRateProvider {
    private static final String SOURCE = "BOK_ECOS_731Y001_0000001";
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ECOS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_RATE_AGE_DAYS = 7;

    private final RestClient client;
    private final String apiKey;

    public BokEcosExchangeRateProvider(RestClient.Builder builder,
            @Value("${BOK_ECOS_API_KEY:}") String apiKey,
            @Value("${atlas.payment.bok-ecos.base-url:https://ecos.bok.or.kr}") String baseUrl) {
        Objects.requireNonNull(builder);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public ExchangeRateQuote latestUsdKrwRate() {
        if (apiKey.isBlank()) throw unavailable();
        LocalDate today = LocalDate.now(KOREA_ZONE);
        String from = today.minusDays(13).format(ECOS_DATE);
        String to = today.format(ECOS_DATE);
        try {
            JsonNode response = client.get()
                    .uri("/api/StatisticSearch/{key}/json/kr/1/10/731Y001/D/{from}/{to}/0000001/",
                            apiKey, from, to)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode rows = response == null ? null : response.path("StatisticSearch").path("row");
            if (rows == null || !rows.isArray()) throw unavailable();
            ExchangeRateQuote latest = StreamSupport.stream(rows.spliterator(), false)
                    .map(BokEcosExchangeRateProvider::quote)
                    .filter(Objects::nonNull)
                    .filter(rate -> !rate.asOfDate().isAfter(today))
                    .max(Comparator.comparing(ExchangeRateQuote::asOfDate))
                    .orElseThrow(BokEcosExchangeRateProvider::unavailable);
            if (ChronoUnit.DAYS.between(latest.asOfDate(), today) > MAX_RATE_AGE_DAYS) throw unavailable();
            return latest;
        } catch (RuntimeException exception) {
            if (exception instanceof PaymentException paymentException) throw paymentException;
            throw unavailable();
        }
    }

    private static ExchangeRateQuote quote(JsonNode row) {
        try {
            LocalDate asOfDate = LocalDate.parse(row.path("TIME").asString(), ECOS_DATE);
            BigDecimal wonPerUsd = new BigDecimal(row.path("DATA_VALUE").asString());
            return wonPerUsd.signum() <= 0 ? null : new ExchangeRateQuote(wonPerUsd, SOURCE, asOfDate);
        } catch (RuntimeException invalidRow) {
            return null;
        }
    }

    private static PaymentException unavailable() {
        return new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                "BOK ECOS exchange-rate data is unavailable");
    }
}
