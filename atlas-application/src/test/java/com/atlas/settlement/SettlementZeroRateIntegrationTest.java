package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.service.SettlementService;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 수수료·보류율이 0 일 때 기존 두 계정 구조가 그대로 유지되는지 확인한다.
 *
 * <p>정책 기본값이 0 이므로, 이 클래스는 프로덕션 기본 동작의 회귀를 막는다. 요율을 쓰는
 * 클래스와 컨텍스트를 분리해야 서로 다른 설정이 섞이지 않는다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementZeroRateIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.settlement.fee-rate-bps", () -> "0");
        registry.add("atlas.settlement.reserve-rate-bps", () -> "0");
    }

    @Autowired private SettlementService settlement;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void zeroRatesKeepLegacyTwoAccountShape() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);

        settlement.recordCapture(new SettlementPostingRequest.Capture(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 5_000));

        Map<String, Long> credits = new TreeMap<>();
        jdbc.query("""
                SELECT account_code, SUM(amount_won) AS total
                FROM settlement_entry WHERE seller_id = ? AND entry_side = 'CREDIT'
                GROUP BY account_code
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("account_code"), resultSet.getLong("total")), sellerId)
                .forEach(entry -> credits.merge(entry.getKey(), entry.getValue(), Long::sum));

        assertThat(credits).containsOnlyKeys("SELLER_PAYABLE");
        assertThat(credits.get("SELLER_PAYABLE")).isEqualTo(5_000L);
    }
}
