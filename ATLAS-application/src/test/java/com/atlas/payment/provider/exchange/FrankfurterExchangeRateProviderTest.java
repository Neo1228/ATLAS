package com.atlas.payment.provider.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.exception.PaymentException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FrankfurterExchangeRateProviderTest {
    private MockWebServer server;
    private FrankfurterExchangeRateProvider provider;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new FrankfurterExchangeRateProvider(RestClient.builder(),
                server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void acceptsFreshRateAndRejectsStaleDate() {
        LocalDate fresh = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        server.enqueue(json("{\"amount\":1.0,\"base\":\"USD\",\"date\":\"" + fresh
                + "\",\"rates\":{\"KRW\":1328.45}}"));
        server.enqueue(json("{\"date\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(30)
                + "\",\"rates\":{\"KRW\":1300}}"));

        ExchangeRateQuote quote = provider.latestUsdKrwRate();

        assertThat(quote.wonPerUsd()).isEqualByComparingTo(new BigDecimal("1328.45"));
        assertThat(quote.asOfDate()).isEqualTo(fresh);
        assertThatThrownBy(provider::latestUsdKrwRate)
                .isInstanceOfSatisfying(PaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    private static MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }
}
