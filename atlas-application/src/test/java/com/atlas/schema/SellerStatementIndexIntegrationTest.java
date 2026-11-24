package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerStatementIndexIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        createStatementTables();
    }

    @Test
    void createsSellerScopedStatementIndexes() {
        jdbc.execute(BaselineSchema.createIndex("settlement_journal_seller_recorded_idx"));

        assertThat(indexDefinition("settlement_journal_seller_recorded_idx"))
                .isEqualTo("CREATE INDEX settlement_journal_seller_recorded_idx "
                        + "ON public.settlement_journal USING btree "
                        + "(seller_id, recorded_at, journal_id)");
        // fixture가 V28 allocation index를 이미 만들었으므로, 이 test가 통과했다는 것은
        // V39가 같은 index를 중복 생성하지 않았다는 뜻이다.
        assertThat(indexDefinition("settlement_seller_allocation_seller_journal_idx"))
                .isEqualTo("CREATE INDEX settlement_seller_allocation_seller_journal_idx "
                        + "ON public.settlement_seller_allocation USING btree (seller_id, journal_id)");
    }

    @Test
    void sellerStatementRangeQueryUsesTheSellerScopedIndexInsteadOfSequentialScan() {
        jdbc.execute(BaselineSchema.createIndex("settlement_journal_seller_recorded_idx"));
        UUID sellerId = UUID.randomUUID();
        UUID otherSeller = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_journal
                    (journal_id, source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
                SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
                       CASE WHEN g % 200 = 0 THEN ?::uuid ELSE ?::uuid END::uuid, 10000,
                       CURRENT_TIMESTAMP - (g || ' minutes')::interval
                FROM generate_series(1, 60000) AS g
                """, sellerId, otherSeller);
        jdbc.execute("ANALYZE settlement_journal");

        List<String> plan = jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT journal_id FROM settlement_journal
                WHERE seller_id = ? AND source_refund_id IS NULL AND checkout_id IS NULL
                  AND recorded_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                  AND recorded_at < CURRENT_TIMESTAMP
                """, String.class, sellerId);

        assertThat(String.join("\n", plan))
                .contains("settlement_journal_seller_recorded_idx")
                .doesNotContain("Seq Scan on settlement_journal");
    }

    private String indexDefinition(String indexName) {
        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, indexName);
    }

    private void createStatementTables() {
        jdbc.execute("""
                CREATE TABLE settlement_journal (
                    journal_id UUID PRIMARY KEY DEFAULT uuidv7(),
                    source_order_id UUID NOT NULL,
                    source_attempt_id UUID,
                    source_refund_id UUID,
                    checkout_id UUID,
                    seller_id UUID,
                    currency CHAR(3) NOT NULL DEFAULT 'KRW',
                    gross_amount_won BIGINT NOT NULL,
                    recorded_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE settlement_seller_allocation (
                    journal_id UUID NOT NULL REFERENCES settlement_journal (journal_id),
                    order_id UUID NOT NULL,
                    seller_id UUID NOT NULL,
                    amount_won BIGINT NOT NULL,
                    allocation_ordinal INTEGER NOT NULL,
                    PRIMARY KEY (journal_id, order_id)
                )
                """);
        // V28에서 이미 존재하는 index. V39가 중복 생성하면 migration이 실패한다.
        jdbc.execute("""
                CREATE INDEX settlement_seller_allocation_seller_journal_idx
                    ON settlement_seller_allocation (seller_id, journal_id)
                """);
    }
}
