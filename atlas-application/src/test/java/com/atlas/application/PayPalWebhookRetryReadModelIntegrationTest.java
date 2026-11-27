package com.atlas.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.application.controller.payment.PayPalWebhookRetryHistoryController;
import com.atlas.application.exception.GlobalExceptionHandler;
import com.atlas.payment.repository.PayPalWebhookRetryHistoryRepository;
import com.atlas.payment.service.impl.PayPalWebhookRetryHistoryServiceImpl;
import org.flywaydb.core.Flyway;
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
        // 테이블을 손으로 만들면 운영 스키마와 어긋난 컬럼(예: 없는 id)이 결함을 가린다(#724). V1 기준 스키마만 실행해도
        // 뒤 마이그레이션이 바꾼 모양과 어긋날 수 있으므로, 다른 Testcontainers 통합 시험처럼 Flyway 전체 체인으로 만든다.
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
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
        insertAttempt("completed", 2, "PROCESSED", "2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z");
        insertAttempt("completed", 1, "CORRELATION_MISS", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
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
        jdbc.update("INSERT INTO paypal_webhook_retry_attempt_history "
                + "(lease_token, event_id, attempt_number, outcome, claimed_at) "
                + "SELECT gen_random_uuid()::text, 'long-lived', n, 'CLAIMED', CURRENT_TIMESTAMP "
                + "FROM generate_series(1, 250) n");
        String body = mvc.perform(get(BASE + "/long-lived/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode attempts = mapper.readTree(body).path("entries");
        assertThat(attempts.isArray()).isTrue();
        assertThat(attempts.size()).isLessThanOrEqualTo(100);
    }

    @Test
    void historyMergesAttemptsAndManualRequeueInDocumentedOrderWithTotal() throws Exception {
        insertAttempt("mixed", 1, "TRANSIENT_ERROR", "2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z");
        insertAttempt("mixed", 2, "EXHAUSTED", "2026-01-02T00:00:00Z", "2026-01-02T00:00:05Z");
        jdbc.update("INSERT INTO paypal_webhook_retry_operator_audit "
                + "(idempotency_key, event_id, actor, reason, outcome, created_at) "
                + "VALUES ('requeue-1', 'mixed', 'operator', 'provider recovered', 'REQUEUED', '2026-01-03T00:00:00Z')");
        // 재큐 뒤 같은 시각에 끝난 두 시도: 시각이 같으면 category, 그다음 attempt_number 내림차순.
        insertAttempt("mixed", 4, "PROCESSED", "2026-01-04T00:00:00Z", "2026-01-04T00:00:05Z");
        insertAttempt("mixed", 3, "LEASE_EXPIRED", "2026-01-04T00:00:00Z", "2026-01-04T00:00:05Z");
        insertAttempt("other", 1, "PROCESSED", "2026-01-05T00:00:00Z", "2026-01-05T00:00:05Z");

        mvc.perform(get(BASE + "/mixed/history").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.entries[0].category").value("LEASE_EXPIRED"))
                .andExpect(jsonPath("$.entries[0].attemptNumber").value(3))
                .andExpect(jsonPath("$.entries[1].category").value("PROCESSED"))
                .andExpect(jsonPath("$.entries[1].attemptNumber").value(4))
                .andExpect(jsonPath("$.entries[2].category").value("MANUAL_REQUEUE"))
                .andExpect(jsonPath("$.entries[2].attemptNumber").doesNotExist())
                .andExpect(jsonPath("$.entries[2].recordedAt").value("2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.entries[3].category").value("EXHAUSTED"))
                .andExpect(jsonPath("$.entries[4].category").value("TRANSIENT_ERROR"));
        mvc.perform(get(BASE + "/mixed/history").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].category").value("MANUAL_REQUEUE"))
                .andExpect(jsonPath("$.entries[1].category").value("EXHAUSTED"));
    }

    private void insertAttempt(String eventId, int attempt, String outcome, String claimedAt, String finishedAt) {
        jdbc.update("INSERT INTO paypal_webhook_retry_attempt_history "
                + "(lease_token, event_id, attempt_number, outcome, claimed_at, finished_at) "
                + "VALUES (gen_random_uuid()::text, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
                eventId, attempt, outcome, claimedAt, finishedAt);
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
