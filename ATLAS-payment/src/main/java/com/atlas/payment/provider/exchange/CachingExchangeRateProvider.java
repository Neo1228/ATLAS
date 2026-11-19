package com.atlas.payment.provider.exchange;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 외부 환율 조회를 짧은 TTL 로 캐시하고, 캐시 미스가 겹치면 한 번만 조회한다.
 *
 * <p>왜 안전한가: BOK ECOS 731Y001 은 일 단위 고시 환율이라 초 단위로 바뀌지 않고, 결제 금액은
 * {@code PaymentAttempt} 에 불변 스냅샷으로 고정된다. 따라서 같은 영업일의 같은 고시값을
 * 수십 초 재사용해도 확정된 결제의 금액이 달라지지 않는다.
 *
 * <p>왜 캐시만으로 부족한가: 만료 직후 동시 요청이 몰리면 N개가 동시에 외부로 나간다
 * (thundering herd). 단일 비행으로 첫 요청만 나가고 나머지는 그 결과를 기다리게 한다.
 *
 * <p>장애 시 동작: 외부 조회가 실패하면 마지막 성공값을 {@code maxStale} 까지 사용한다.
 * 현재는 외부 장애가 결제 전면 중단이므로, 몇 분 된 고시값으로 결제를 계속하는 편이 낫다.
 * 허용 범위를 넘으면 예외를 그대로 올려 결제를 막는다(낡은 환율로 조용히 결제하지 않는다).
 */
@Component
@Primary
public class CachingExchangeRateProvider implements ExchangeRateProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachingExchangeRateProvider.class);

    private final ExchangeRateProvider delegate;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile ExchangeRateQuote cached;
    private volatile Instant cachedAt;

    public CachingExchangeRateProvider(
            BokEcosExchangeRateProvider delegate,
            Clock clock,
            @Value("${atlas.payment.fx.cache-ttl:PT60S}") Duration ttl,
            @Value("${atlas.payment.fx.max-stale:PT10M}") Duration maxStale) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        this.maxStale = Objects.requireNonNull(maxStale);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("fx cache ttl must be positive");
        }
        if (maxStale.isNegative() || maxStale.compareTo(ttl) < 0) {
            throw new IllegalArgumentException("fx max-stale must be at least the cache ttl");
        }
    }

    @Override
    public ExchangeRateQuote latestUsdKrwRate() {
        ExchangeRateQuote snapshot = cached;
        Instant fetchedAt = cachedAt;
        if (snapshot != null && fetchedAt != null && isFresh(fetchedAt)) {
            return snapshot;
        }
        // 만료 직후 동시 요청은 여기서 줄을 서고, 먼저 들어온 요청만 실제로 조회한다.
        refreshLock.lock();
        try {
            snapshot = cached;
            fetchedAt = cachedAt;
            if (snapshot != null && fetchedAt != null && isFresh(fetchedAt)) {
                return snapshot;
            }
            try {
                ExchangeRateQuote refreshed = delegate.latestUsdKrwRate();
                cached = refreshed;
                cachedAt = clock.instant();
                return refreshed;
            } catch (RuntimeException failure) {
                if (snapshot != null && fetchedAt != null && withinMaxStale(fetchedAt)) {
                    LOGGER.warn("Exchange-rate refresh failed; serving cached quote. age={}s failure={}",
                            Duration.between(fetchedAt, clock.instant()).toSeconds(),
                            failure.getClass().getSimpleName());
                    return snapshot;
                }
                throw failure;
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isFresh(Instant fetchedAt) {
        return Duration.between(fetchedAt, clock.instant()).compareTo(ttl) < 0;
    }

    private boolean withinMaxStale(Instant fetchedAt) {
        return Duration.between(fetchedAt, clock.instant()).compareTo(maxStale) <= 0;
    }

    /**
     * 캐시 상태를 비운다. 운영 경로에서는 호출하지 않는다.
     *
     * <p>테스트가 결제 경로를 관찰할 때 이전 테스트가 데운 캐시 때문에 "외부 호출 0회"가
     * 나오는 것을 막는다. 통합 테스트는 Spring context 를 공유하므로 명시적 초기화가 필요하다.
     */
    public void clear() {
        refreshLock.lock();
        try {
            cached = null;
            cachedAt = null;
        } finally {
            refreshLock.unlock();
        }
    }
}
