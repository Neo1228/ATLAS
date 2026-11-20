package com.atlas.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.exchange.BokEcosExchangeRateProvider;
import com.atlas.payment.provider.exchange.CachingExchangeRateProvider;
import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 환율 조회가 불가능할 때 결제가 500(INTERNAL_ERROR)이 아니라
 * {@code PAYMENT_PROVIDER_UNAVAILABLE}(503 + 재시도 안내)로 드러나는지 고정한다.
 */
class FxConversionServiceProviderUnavailableTest {
    private static final ExchangeRateQuote QUOTE = new ExchangeRateQuote(
            new BigDecimal("1332.4"), "BOK_ECOS_731Y001_0000001", LocalDate.of(2026, 11, 20));
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    @Test
    void providerReturningNoQuoteSurfacesAsProviderUnavailableInsteadOfNullPointer() {
        // provider 가 계약을 어기고 null 을 돌려줘도 NPE 로 새지 않아야 한다.
        FxConversionService service = new FxConversionService(() -> null);

        assertProviderUnavailable(service);
    }

    @Test
    void coldCacheWithFailingUpstreamSurfacesAsProviderUnavailable() {
        // 재시작 직후 캐시가 비어 있고 ECOS 도 응답하지 않는 상황이다.
        FxConversionService service = new FxConversionService(caching(() -> {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE, "ECOS down");
        }, new MutableClock(NOW)));

        assertProviderUnavailable(service);
    }

    @Test
    void coldCacheWithEmptyUpstreamAnswerSurfacesAsProviderUnavailableAndRecoversOnRetry() {
        ExchangeRateQuote[] upstream = {null};
        FxConversionService service = new FxConversionService(caching(() -> upstream[0], new MutableClock(NOW)));

        assertProviderUnavailable(service);

        // 빈 응답을 캐시하지 않으므로 upstream 이 회복되면 같은 요청을 재시도해 바로 환산된다.
        upstream[0] = QUOTE;
        FxConversionService.Conversion conversion = service.convertKrwToUsdCents(1_000);
        assertThat(conversion.quote()).isEqualTo(QUOTE);
        assertThat(conversion.usdCents()).isEqualTo(75);
    }

    @Test
    void emptyUpstreamAnswerAfterTtlServesLastGoodQuoteWithinMaxStale() {
        ExchangeRateQuote[] upstream = {QUOTE};
        MutableClock clock = new MutableClock(NOW);
        FxConversionService service = new FxConversionService(caching(() -> upstream[0], clock));
        service.convertKrwToUsdCents(1_000);

        upstream[0] = null;
        clock.advance(Duration.ofSeconds(61));

        // 빈 응답은 조회 실패와 같게 취급되어 허용 범위 안의 직전 고시값을 쓴다.
        FxConversionService.Conversion conversion = service.convertKrwToUsdCents(1_000);
        assertThat(conversion.quote()).isEqualTo(QUOTE);
        assertThat(conversion.usdCents()).isEqualTo(75);

        // 허용 범위를 넘으면 낡은 환율 대신 일시 장애로 알린다.
        clock.advance(Duration.ofMinutes(10));
        assertProviderUnavailable(service);
    }

    @Test
    void freshQuoteStillConverts() {
        FxConversionService service = new FxConversionService(caching(() -> QUOTE, new MutableClock(NOW)));

        FxConversionService.Conversion conversion = service.convertKrwToUsdCents(1_000);

        assertThat(conversion.quote()).isEqualTo(QUOTE);
        assertThat(conversion.usdCents()).isEqualTo(75);
    }

    private static void assertProviderUnavailable(FxConversionService service) {
        assertThatThrownBy(() -> service.convertKrwToUsdCents(1_000))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    private static CachingExchangeRateProvider caching(Supplier<ExchangeRateQuote> upstream, Clock clock) {
        BokEcosExchangeRateProvider delegate = mock(BokEcosExchangeRateProvider.class);
        when(delegate.latestUsdKrwRate()).thenAnswer(invocation -> upstream.get());
        return new CachingExchangeRateProvider(delegate, clock, Duration.ofSeconds(60), Duration.ofMinutes(10));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration amount) {
            current = current.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
