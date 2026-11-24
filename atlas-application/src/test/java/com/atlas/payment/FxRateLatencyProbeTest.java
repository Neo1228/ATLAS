package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.provider.exchange.CachingExchangeRateProvider;
import com.atlas.payment.service.impl.FxConversionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 환율 조회 1회의 wall-clock 을 표본으로 남긴다.
 *
 * <p>지연 자체를 주장하려는 테스트가 아니다. EXP-041 의 측정을 재현하는 도구다.
 * 캐시 도입 전후를 같은 조건에서 비교하려면 이 표본을 다시 뽑을 수 있어야 한다.
 *
 * <p>사용법: {@code FX_STUB_LATENCY_MS=80 ./gradlew :atlas-application:test --tests '*FxRateLatencyProbeTest' --rerun-tasks}
 * 결과는 테스트 출력의 {@code FX_LOOKUP_SAMPLES} 줄에 남는다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class FxRateLatencyProbeTest extends PayPalIntegrationTestSupport {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
    }

    @Autowired private FxConversionService fx;
    @Autowired private CachingExchangeRateProvider fxCache;

    @BeforeEach
    void resetFxCache() {
        // 같은 Spring context 를 공유하므로 이전 테스트가 데운 캐시를 비운다.
        fxCache.clear();
    }

    @Test
    void printsExchangeRateLookupLatencySamples() {
        List<Double> samples = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            long start = System.nanoTime();
            fx.convertKrwToUsdCents(17_200L);
            samples.add((System.nanoTime() - start) / 1e6);
        }
        Collections.sort(samples);
        double p50 = samples.get(samples.size() / 2);
        double p99 = samples.get(samples.size() - 1);
        System.out.printf("FX_LOOKUP_SAMPLES p50=%.2fms max=%.2fms n=%d%n", p50, p99, samples.size());
        assertThat(samples).isNotEmpty();
    }
}
