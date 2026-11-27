package com.atlas.application.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.service.SettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 켠 점검이 정상 흐름에서는 모든 게이지를 0으로, 주입한 위반은 해당 게이지만 1로 올리는지 확인한다(EXP-111). */
@Testcontainers
@SpringBootTest
@DirtiesContext
class LedgerAuditMetricsIntegrationTest extends PayPalIntegrationTestSupport {
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
        registry.add("atlas.settlement.ledger-audit.enabled", () -> "true");
        registry.add("atlas.settlement.ledger-audit.initial-delay", () -> "PT24H");
        registry.add("atlas.settlement.ledger-audit.poll-interval", () -> "PT24H");
    }

    @Autowired private LedgerAuditMetrics metrics;
    @Autowired private MeterRegistry meters;
    @Autowired private SettlementService settlement;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    void gaugesStayZeroForFlowDataAndRaiseOnlyTheViolatedCheck() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));

        metrics.refresh();
        assertThat(gauge("JOURNAL_UNBALANCED")).isZero();
        assertThat(gauge("JOURNAL_WITHOUT_ENTRIES")).isZero();
        assertThat(meters.get("settlement.ledger.audit.lastrunepochseconds").gauge().value()).isPositive();

        // 소유 계정이 가드를 우회해 항목 없는 분개를 넣은 경우(복제 역할로 지연 제약 트리거를 건너뜀)
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency,
                        gross_amount_won, recorded_at) VALUES (?, ?, ?, 'KRW', 100, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), sellerId);
        });
        metrics.refresh();
        assertThat(gauge("JOURNAL_WITHOUT_ENTRIES")).isEqualTo(1.0);
        assertThat(gauge("JOURNAL_UNBALANCED")).isZero();
        assertThat(meters.find("settlement.ledger.audit.refreshfailures").counter()).isNull();
    }

    private double gauge(String check) {
        return meters.get("settlement.ledger.audit.violations").tag("check", check).gauge().value();
    }
}
