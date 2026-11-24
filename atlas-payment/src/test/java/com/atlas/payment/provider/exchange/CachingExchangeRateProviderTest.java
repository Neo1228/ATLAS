package com.atlas.payment.provider.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.response.ExchangeRateQuoteResponse;
import com.atlas.payment.exception.PaymentException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CachingExchangeRateProviderTest {
    private static final ExchangeRateQuoteResponse QUOTE = new ExchangeRateQuoteResponse(
            new BigDecimal("1332.4"), "BOK_ECOS_731Y001_0000001", LocalDate.of(2026, 11, 20));

    @Test
    void repeatedLookupsWithinTtlHitTheDelegateOnce() {
        AtomicInteger calls = new AtomicInteger();
        // 결제가 반복되어도 같은 고시값을 재사용해야 외부 호출이 늘지 않는다.
        CachingExchangeRateProvider provider =
                provider(calls, () -> QUOTE, Instant.parse("2026-11-20T00:00:00Z"));

        for (int index = 0; index < 10; index++) {
            assertThat(provider.latestUsdKrwRate()).isEqualTo(QUOTE);
        }

        assertThat(calls).hasValue(1);
    }

    @Test
    void ttlExpiryTriggersExactlyOneRefresh() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        CachingExchangeRateProvider provider = new CachingExchangeRateProvider(
                delegate(calls, () -> QUOTE), clock, Duration.ofSeconds(60), Duration.ofMinutes(10));

        provider.latestUsdKrwRate();
        clock.advance(Duration.ofSeconds(61));
        provider.latestUsdKrwRate();
        provider.latestUsdKrwRate();

        assertThat(calls).hasValue(2);
    }

    @Test
    void concurrentColdStartCollapsesToASingleExternalCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        // 첫 호출을 붙잡아 나머지 스레드가 동시에 캐시 미스에 도달하게 만든다.
        CachingExchangeRateProvider provider = provider(calls, () -> {
            entered.countDown();
            try {
                Thread.sleep(120);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return QUOTE;
        }, Instant.parse("2026-11-20T00:00:00Z"));

        int threads = 8;
        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Future<ExchangeRateQuoteResponse>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(provider::latestUsdKrwRate));
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            for (Future<ExchangeRateQuoteResponse> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(QUOTE);
            }
        }

        // thundering herd 방지: 8개 동시 요청이 외부 호출 1회로 접힌다.
        assertThat(calls).hasValue(1);
    }

    @Test
    void failureServesTheLastGoodQuoteWhileWithinMaxStale() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        boolean[] healthy = {true};
        CachingExchangeRateProvider provider = new CachingExchangeRateProvider(
                delegate(calls, () -> {
                    if (!healthy[0]) {
                        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                                "provider down");
                    }
                    return QUOTE;
                }), clock, Duration.ofSeconds(60), Duration.ofMinutes(10));

        provider.latestUsdKrwRate();
        healthy[0] = false;
        clock.advance(Duration.ofSeconds(90));

        // 외부가 죽어도 허용 staleness 안이면 결제를 계속할 수 있어야 한다.
        assertThat(provider.latestUsdKrwRate()).isEqualTo(QUOTE);
    }

    @Test
    void failureBeyondMaxStaleFailsInsteadOfSilentlyUsingOldRates() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        boolean[] healthy = {true};
        CachingExchangeRateProvider provider = new CachingExchangeRateProvider(
                delegate(calls, () -> {
                    if (!healthy[0]) {
                        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                                "provider down");
                    }
                    return QUOTE;
                }), clock, Duration.ofSeconds(60), Duration.ofMinutes(10));

        provider.latestUsdKrwRate();
        healthy[0] = false;
        clock.advance(Duration.ofMinutes(11));

        // 허용 범위를 넘은 낡은 환율로 조용히 결제하지 않는다.
        assertThatThrownBy(provider::latestUsdKrwRate).isInstanceOf(PaymentException.class);
    }

    @Test
    void failureBackoffStopsRepeatedProviderCallsWhileServingTheStaleQuote() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        boolean[] healthy = {true};
        CachingExchangeRateProvider provider = new CachingExchangeRateProvider(
                delegate(calls, () -> {
                    if (!healthy[0]) {
                        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                                "provider down");
                    }
                    return QUOTE;
                }), clock, Duration.ofSeconds(60), Duration.ofMinutes(10), Duration.ofSeconds(15));
        provider.latestUsdKrwRate();
        healthy[0] = false;
        clock.advance(Duration.ofSeconds(90));

        // 첫 실패 뒤 backoff(최대 18초) 안에서는 요청이 몇 번이든 외부를 다시 부르지 않고 낡은 값을 바로 받는다.
        for (int index = 0; index < 20; index++) {
            assertThat(provider.latestUsdKrwRate()).isEqualTo(QUOTE);
            clock.advance(Duration.ofMillis(500));
        }

        assertThat(calls).hasValue(2);
    }

    @Test
    void providerIsCalledAgainOnceTheBackoffHasPassedAndRecoveryRefreshesTheQuote() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        boolean[] healthy = {true};
        ExchangeRateQuoteResponse recovered = new ExchangeRateQuoteResponse(
                new BigDecimal("1340.0"), "BOK_ECOS_731Y001_0000002", LocalDate.of(2026, 11, 21));
        CachingExchangeRateProvider provider = new CachingExchangeRateProvider(
                delegate(calls, () -> {
                    if (!healthy[0]) {
                        throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                                "provider down");
                    }
                    return calls.get() == 1 ? QUOTE : recovered;
                }), clock, Duration.ofSeconds(60), Duration.ofMinutes(10), Duration.ofSeconds(15));
        provider.latestUsdKrwRate();
        healthy[0] = false;
        clock.advance(Duration.ofSeconds(90));
        provider.latestUsdKrwRate();

        healthy[0] = true;
        clock.advance(Duration.ofSeconds(20));

        assertThat(provider.latestUsdKrwRate()).isEqualTo(recovered);
        assertThat(calls).hasValue(3);
    }

    @Test
    void rejectsMisconfiguredStalenessWindow() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new CachingExchangeRateProvider(
                delegate(calls, () -> QUOTE), Clock.systemUTC(),
                Duration.ofMinutes(10), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CachingExchangeRateProvider provider(AtomicInteger calls,
            Supplier<ExchangeRateQuoteResponse> supplier, Instant now) {
        return new CachingExchangeRateProvider(delegate(calls, supplier),
                Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(60), Duration.ofMinutes(10));
    }

    private static BokEcosExchangeRateProvider delegate(AtomicInteger calls,
            Supplier<ExchangeRateQuoteResponse> supplier) {
        BokEcosExchangeRateProvider delegate = mock(BokEcosExchangeRateProvider.class);
        when(delegate.latestUsdKrwRate()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return supplier.get();
        });
        return delegate;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
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
