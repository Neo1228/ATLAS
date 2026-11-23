package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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
class AdmissionExpiryIndexIntegrationTest {
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
        jdbc.execute("""
                CREATE TABLE admission_ticket (
                    ticket_id UUID PRIMARY KEY,
                    seller_id UUID NOT NULL,
                    sku_id VARCHAR(128) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX admission_ticket_expiry_idx ON admission_ticket (expires_at) "
                + "WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE')");
        jdbc.execute("DROP INDEX admission_ticket_expiry_idx");
        jdbc.execute(BaselineSchema.createIndex("admission_ticket_expiry_idx"));
    }

    @Test
    void firstAndKeysetCandidatePagesUseTheCompositeCoveringIndex() {
        jdbc.update("""
                INSERT INTO admission_ticket (ticket_id, seller_id, sku_id, status, expires_at)
                SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'sku-' || (g % 10000),
                       'WAITING', CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                FROM generate_series(1, 60000) AS g
                """);
        jdbc.update("""
                INSERT INTO admission_ticket (ticket_id, seller_id, sku_id, status, expires_at)
                SELECT gen_random_uuid(), md5((g % 100)::text)::uuid, 'sku-' || (g % 10000),
                       'WAITING', CURRENT_TIMESTAMP + INTERVAL '10 minutes'
                FROM generate_series(1, 60000) AS g
                """);
        jdbc.execute("VACUUM (ANALYZE) admission_ticket");

        List<String> firstPagePlan = jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT ticket_id, seller_id, sku_id, expires_at
                FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
                ORDER BY expires_at, ticket_id
                LIMIT 200
                """, String.class);
        Object[] cursor = jdbc.queryForObject("""
                SELECT expires_at, ticket_id FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
                ORDER BY expires_at, ticket_id OFFSET 199 LIMIT 1
                """, (result, rowNumber) -> new Object[] {
                    result.getTimestamp("expires_at"), result.getObject("ticket_id", UUID.class)
                });
        Timestamp cursorAt = (Timestamp) cursor[0];
        UUID cursorId = (UUID) cursor[1];

        List<String> nextPagePlan = jdbc.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT ticket_id, seller_id, sku_id, expires_at
                FROM admission_ticket
                WHERE status IN ('WAITING', 'CHECKOUT_ACTIVE') AND expires_at <= CURRENT_TIMESTAMP
                  AND (expires_at, ticket_id) > (?, ?)
                ORDER BY expires_at, ticket_id
                LIMIT 200
                """, String.class, cursorAt, cursorId);

        assertKeysetIndexPlan(firstPagePlan);
        assertKeysetIndexPlan(nextPagePlan);
        assertThat(indexDefinition()).contains("(expires_at, ticket_id)")
                .doesNotContain("INCLUDE");
    }

    private String indexDefinition() {
        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'admission_ticket_expiry_idx'",
                String.class);
    }

    private static void assertKeysetIndexPlan(List<String> plan) {
        String text = String.join("\n", plan);
        assertThat(text)
                .contains("admission_ticket_expiry_idx")
                .doesNotContain("Sort", "Seq Scan on admission_ticket");
    }
}
