package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.controller.PayPalWebhookRetryHistoryController;
import com.atlas.application.exception.GlobalExceptionHandler;
import com.atlas.payment.repository.PayPalWebhookRetryHistoryRepository;
import com.atlas.payment.service.impl.PayPalWebhookRetryHistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class PayPalWebhookRetryReadModelIntegrationTest {
    private static final String BASE = "/api/internal/payment/paypal/webhook-retries";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS paypal_webhook_retry_operator_audit CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS paypal_webhook_retry_attempt_history CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS paypal_webhook_retry CASCADE");
        jdbc.execute("CREATE TABLE paypal_webhook_retry (event_id VARCHAR(128) PRIMARY KEY, "
                + "attempt_count INTEGER NOT NULL, state VARCHAR(16) NOT NULL, event_type VARCHAR(128), "
                + "next_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, lease_owner VARCHAR(128), lease_until TIMESTAMPTZ)");
        // V54 와 동일하게 recorded_at 생성 컬럼을 둔다. 요약 쿼리가 COALESCE 대신 이 컬럼을
        // 쓰므로, 스키마를 손으로 만드는 이 테스트도 같은 모양이어야 한다.
        jdbc.execute("CREATE TABLE paypal_webhook_retry_attempt_history (id BIGINT GENERATED ALWAYS AS IDENTITY, "
                + "event_id VARCHAR(128) NOT NULL, event_type VARCHAR(128), attempt_number INTEGER NOT NULL, "
                + "lease_token VARCHAR(128), outcome VARCHAR(32) NOT NULL, claimed_at TIMESTAMPTZ NOT NULL, "
                + "finished_at TIMESTAMPTZ, recorded_at TIMESTAMPTZ "
                + "GENERATED ALWAYS AS (COALESCE(finished_at, claimed_at)) STORED)");
        jdbc.execute("CREATE TABLE paypal_webhook_retry_operator_audit (idempotency_key VARCHAR(128), "
                + "event_id VARCHAR(128), actor VARCHAR(128), reason VARCHAR(500), "
                + "outcome VARCHAR(16), created_at TIMESTAMPTZ)");
        mvc = MockMvcBuilders.standaloneSetup(new PayPalWebhookRetryHistoryController(
                new PayPalWebhookRetryHistoryServiceImpl(new PayPalWebhookRetryHistoryRepository(jdbc))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void summaryAndPendingPageReflectQueueStateAndStableOrdering() throws Exception {
        insertRetry("evt-c", 3, "PENDING");
        insertRetry("evt-a", 1, "PENDING");
        insertRetry("evt-b", 2, "PENDING");
        insertRetry("evt-exhausted", 5, "EXHAUSTED");

        mvc.perform(get(BASE + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(3))
                .andExpect(jsonPath("$.exhausted").value(1));
        mvc.perform(get(BASE + "/pending").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("evt-b"));
    }

    @Test
    void pendingPaginationRejectsInvalidAndExcessiveOffsetsAndCapsSize() throws Exception {
        mvc.perform(get(BASE + "/pending").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/pending").param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/pending").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/pending").param("size", "1000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryReportsOldestQueueTimesAndOnlyExpiredLeases() throws Exception {
        insertRetry("pending-old", 1, "PENDING");
        insertRetry("pending-new", 1, "PENDING");
        insertRetry("exhausted-old", 3, "EXHAUSTED");
        insertRetry("exhausted-new", 4, "EXHAUSTED");
        jdbc.update("UPDATE paypal_webhook_retry SET created_at = '2026-01-01T00:00:00Z', "
                + "lease_owner = 'expired-worker', lease_until = CURRENT_TIMESTAMP - interval '1 hour' "
                + "WHERE event_id = 'pending-old'");
        jdbc.update("UPDATE paypal_webhook_retry SET created_at = '2026-01-02T00:00:00Z', "
                + "lease_owner = 'active-worker', lease_until = CURRENT_TIMESTAMP + interval '1 hour' "
                + "WHERE event_id = 'pending-new'");
        jdbc.update("UPDATE paypal_webhook_retry SET finished_at = '2026-01-03T00:00:00Z' "
                + "WHERE event_id = 'exhausted-old'");
        jdbc.update("UPDATE paypal_webhook_retry SET finished_at = '2026-01-04T00:00:00Z' "
                + "WHERE event_id = 'exhausted-new'");
        mvc.perform(get(BASE + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldestPendingSince").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.oldestExhaustedSince").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.expiredLeaseCount").value(1));
    }

    @Test
    void historySurvivesQueueRemovalAndUnknownIsNotFound() throws Exception {
        insertRetry("still-queued", 0, "PENDING");
        jdbc.update("INSERT INTO paypal_webhook_retry_attempt_history "
                + "(event_id, attempt_number, outcome, claimed_at, finished_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                "completed", 2, "PROCESSED", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z");
        jdbc.update("INSERT INTO paypal_webhook_retry_attempt_history "
                + "(event_id, attempt_number, outcome, claimed_at, finished_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                "completed", 1, "CORRELATION_MISS", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        mvc.perform(get(BASE + "/completed/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("completed"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].category").value("PROCESSED"))
                .andExpect(jsonPath("$.entries[1].category").value("CORRELATION_MISS"));
        mvc.perform(get(BASE + "/still-queued/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(get(BASE + "/missing/history")).andExpect(status().isNotFound());
    }

    @Test
    void historyIsBoundedForLongLivedEvents() throws Exception {
        jdbc.update("INSERT INTO paypal_webhook_retry_attempt_history (event_id, attempt_number, outcome, claimed_at) "
                + "SELECT 'long-lived', n, 'CLAIMED', CURRENT_TIMESTAMP FROM generate_series(1, 250) n");
        String body = mvc.perform(get(BASE + "/long-lived/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode attempts = mapper.readTree(body).path("entries");
        assertThat(attempts.isArray()).isTrue();
        assertThat(attempts.size()).isLessThanOrEqualTo(100);
    }

    private void insertRetry(String eventId, int attempts, String status) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry
                    (event_id, event_type, attempt_count, state, next_attempt_at, created_at, finished_at)
                VALUES (?, 'TEST.EVENT', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'EXHAUSTED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, eventId, attempts, status, status);
}
}
