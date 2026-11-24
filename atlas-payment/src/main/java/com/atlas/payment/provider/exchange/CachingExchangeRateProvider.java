package com.atlas.payment.provider.exchange;

import com.atlas.payment.dto.response.FxResponse;
import com.atlas.payment.exception.PaymentException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>장애 중 재시도 폭주 방지: 실패하면 {@code failureBackoff} 동안은 외부를 다시 부르지 않는다. backoff가
 * 없으면 TTL이 지난 뒤 모든 요청이 실패할 외부 호출(최대 약 5초)을 하고 락 뒤에서 줄을 서, 호출 수가 요청률과
 * replica 수에 비례하고 429 같은 차단 응답에서는 차단을 연장한다. backoff 중에는 락 없이 낡은 값을 바로
 * 돌려준다. 돌려줄 값이 없으면(콜드 캐시 또는 허용 범위 밖) backoff를 적용하지 않고 계속 조회한다. 이때는
 * 외부를 부르지 않으면 결제를 막을 뿐 얻는 것이 없고, 제공자가 회복하는 즉시 결제가 이어져야 하기 때문이다.
 */
@Component
@Primary
public class CachingExchangeRateProvider implements ExchangeRateProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(CachingExchangeRateProvider.class);
    private static final Duration DEFAULT_FAILURE_BACKOFF = Duration.ofSeconds(15);
    /** 여러 replica가 같은 순간에 다시 부르지 않도록 backoff에 더하는 무작위 폭(±20%). */
    private static final double BACKOFF_JITTER = 0.2;

    private final ExchangeRateProvider delegate;
    private final Clock clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private final Duration failureBackoff;

    private volatile FxResponse.ExchangeRateQuote cached;
    private volatile Instant cachedAt;
    /** 이 시각 전에는 외부를 다시 부르지 않는다. 마지막 조회가 실패했을 때만 설정된다. */
    private volatile Instant retryNotBefore;

    public CachingExchangeRateProvider(
            BokEcosExchangeRateProvider delegate,
            Clock clock,
            Duration ttl,
            Duration maxStale) {
        this(delegate, clock, ttl, maxStale, DEFAULT_FAILURE_BACKOFF);
    }

    @Autowired
    public CachingExchangeRateProvider(
            BokEcosExchangeRateProvider delegate,
            Clock clock,
            @Value("${atlas.payment.fx.cache-ttl:PT60S}") Duration ttl,
            @Value("${atlas.payment.fx.max-stale:PT10M}") Duration maxStale,
            @Value("${atlas.payment.fx.failure-backoff:PT15S}") Duration failureBackoff) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        this.maxStale = Objects.requireNonNull(maxStale);
        this.failureBackoff = Objects.requireNonNull(failureBackoff);
        if (failureBackoff.isNegative()) {
            throw new IllegalArgumentException("fx failure backoff must not be negative");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("fx cache ttl must be positive");
        }
        if (maxStale.isNegative() || maxStale.compareTo(ttl) < 0) {
            throw new IllegalArgumentException("fx max-stale must be at least the cache ttl");
        }
    }

    @Override
    public FxResponse.ExchangeRateQuote latestUsdKrwRate() {
        FxResponse.ExchangeRateQuote snapshot = cached;
        Instant fetchedAt = cachedAt;
        if (snapshot != null && fetchedAt != null && isFresh(fetchedAt)) {
            return snapshot;
        }
        // 직전 조회가 실패했다면 backoff가 끝나기 전에는 락도 외부 호출도 없이 바로 결정한다.
        if (backingOff() && usable(snapshot, fetchedAt)) {
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
            // 락을 기다리는 사이 앞선 요청이 실패해 backoff가 시작됐을 수 있다.
            if (backingOff() && usable(snapshot, fetchedAt)) {
                return snapshot;
            }
            try {
                FxResponse.ExchangeRateQuote refreshed = delegate.latestUsdKrwRate();
                // null 을 캐시하면 TTL 동안 모든 결제가 환율 없이 실패한다. 조회 실패로 취급해
                // 허용 범위 안의 직전 값으로 버티거나, 없으면 결제 대행사 일시 장애로 알린다.
                if (refreshed == null) {
                    throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE,
                            "Exchange-rate provider returned no quote");
                }
                cached = refreshed;
                cachedAt = clock.instant();
                retryNotBefore = null;
                return refreshed;
            } catch (RuntimeException failure) {
                retryNotBefore = clock.instant().plus(jittered(failureBackoff));
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

    /** 직전 조회가 실패해 아직 backoff 구간이면 true. */
    private boolean backingOff() {
        Instant notBefore = retryNotBefore;
        return notBefore != null && clock.instant().isBefore(notBefore);
    }

    /** 허용 범위 안의 마지막 성공값이 있어 외부를 부르지 않고도 응답할 수 있는지. */
    private boolean usable(FxResponse.ExchangeRateQuote snapshot, Instant fetchedAt) {
        return snapshot != null && fetchedAt != null && withinMaxStale(fetchedAt);
    }

    private static Duration jittered(Duration base) {
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
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
            retryNotBefore = null;
        } finally {
            refreshLock.unlock();
        }
    }
}
