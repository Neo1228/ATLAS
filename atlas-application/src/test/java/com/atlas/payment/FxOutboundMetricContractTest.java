package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.service.impl.FxConversionService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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
 * 외부 환율 API 호출이 코드 변경 없이 지표로 관측되는지 고정한다.
 *
 * <p>Spring Boot 는 micrometer-observation 이 classpath 에 있으면 {@code RestClient} 에 자동
 * 계측을 붙인다. 그래서 환율 API 호출량·지연은 {@code http.client.requests} 로 이미 노출된다.
 *
 * <p>이 테스트가 깨지면 그 전제가 사라진 것이다. 그때는 도메인에 카운터를 넣을지,
 * 계측 설정을 명시할지 다시 판단해야 한다(공유 캐시 도입 판단의 입력이 사라지므로).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class FxOutboundMetricContractTest extends PayPalIntegrationTestSupport {
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
        registry.add("management.metrics.distribution.percentiles-histogram.http.client.requests", () -> "true");
    }

    @Autowired private FxConversionService fx;
    @Autowired private MeterRegistry meters;

    @Test
    void outboundExchangeRateCallsAreVisibleInTheMeterRegistry() {
        fx.convertKrwToUsdCents(17_200L);

        List<Meter> clientMeters = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("http.client"))
                .toList();

        assertThat(clientMeters)
                .as("outbound HTTP metrics should exist without custom counters")
                .isNotEmpty();

        // 환율 API 경로가 실제로 태깅되어야 replica 별 호출률을 뽑을 수 있다.
        assertThat(clientMeters)
                .anySatisfy(meter -> assertThat(meter.getId().getTag("uri"))
                        .as("uri tag identifying the exchange-rate endpoint")
                        .contains("StatisticSearch"));
    }
}
