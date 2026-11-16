package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.service.PayPalWebhookRetryRepository;
import com.atlas.application.service.PayPalWebhookRetryRepository.ClaimedRetry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PayPalWebhookRetryHistoryMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PayPalWebhookRetryRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        apply("V31__paypal_webhook_retry_queue.sql");
        repository = new PayPalWebhookRetryRepository(jdbc);
    }

    private void apply(String migration) throws IOException {
        ClassPathResource resource = new ClassPathResource("db/migration/" + migration);
        try (var input = resource.getInputStream()) {
            jdbc.execute(StreamUtils.copyToString(input, StandardCharsets.UTF_8));
        }
    }

    private void insertQueueRow(String eventId, int attempts) {
        jdbc.update("""
                INSERT INTO paypal_webhook_retry
                    (event_id, event_type, attempt_count, next_attempt_at, created_at)
                VALUES (?, 'PAYMENT.CAPTURE.COMPLETED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, eventId, attempts);
    }

    private int historyCount(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history WHERE event_id = ?",
                Integer.class, eventId);
    }

    @Test
    void claimingAndRecordingHistoryRollBackTogether() throws IOException {
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        insertQueueRow("evt-atomic", 0);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            assertThat(repository.claimDue()).hasSize(1);
            assertThat(historyCount("evt-atomic")).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(historyCount("evt-atomic")).isZero();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM paypal_webhook_retry WHERE event_id = ?",
                Integer.class, "evt-atomic")).isZero();
        assertThat(repository.claimDue()).hasSize(1);
        assertThat(historyCount("evt-atomic")).isEqualTo(1);
    }

    @Test
    void expiredLeaseCanBeReclaimedButStaleWorkerCannotChangeItsHistoryOrQueue() throws IOException {
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        insertQueueRow("evt-lease", 0);
        ClaimedRetry first = repository.claimDue().get(0);
        jdbc.update("UPDATE paypal_webhook_retry SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE event_id = ?",
                "evt-lease");
        ClaimedRetry second = repository.claimDue().get(0);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.leaseOwner()).isNotEqualTo(first.leaseOwner());
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM paypal_webhook_retry_attempt_history WHERE lease_token = ?
                """, String.class, first.leaseOwner())).isEqualTo("LEASE_EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM paypal_webhook_retry_attempt_history WHERE lease_token = ?
                """, String.class, second.leaseOwner())).isEqualTo("CLAIMED");
        assertThat(historyCount("evt-lease")).isEqualTo(2);

        assertThat(repository.reschedule("evt-lease", first.attempt(), first.leaseOwner(),
                PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR, "stale failure")).isFalse();
        assertThat(repository.complete("evt-lease", first.leaseOwner())).isFalse();
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM paypal_webhook_retry WHERE event_id = ?",
                String.class, "evt-lease")).isEqualTo(second.leaseOwner());
        assertThat(historyCount("evt-lease")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM paypal_webhook_retry_attempt_history WHERE lease_token = ?
                """, String.class, second.leaseOwner())).isEqualTo("CLAIMED");

        assertThat(repository.complete("evt-lease", second.leaseOwner())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?",
                Integer.class, "evt-lease")).isZero();
        assertThat(historyCount("evt-lease")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM paypal_webhook_retry_attempt_history WHERE lease_token = ?
                """, String.class, second.leaseOwner())).isEqualTo("PROCESSED");
    }

    @Test
    void legacyQueueAndRecreatedEventRetainTheirDistinctClaims() throws IOException {
        insertQueueRow("evt-legacy", 3);
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        assertThat(historyCount("evt-legacy")).isZero();
        ClaimedRetry legacy = repository.claimDue().get(0);
        assertThat(legacy.attempt()).isEqualTo(4);
        repository.complete("evt-legacy", legacy.leaseOwner());
        assertThat(historyCount("evt-legacy")).isEqualTo(1);

        insertQueueRow("evt-legacy", 0);
        ClaimedRetry recreated = repository.claimDue().get(0);
        assertThat(recreated.attempt()).isEqualTo(1);
        repository.complete("evt-legacy", recreated.leaseOwner());
        assertThat(historyCount("evt-legacy")).isEqualTo(2);
        insertQueueRow("evt-legacy", 0);
        ClaimedRetry recreatedAgain = repository.claimDue().get(0);
        assertThat(recreatedAgain.attempt()).isEqualTo(1);
        repository.complete("evt-legacy", recreatedAgain.leaseOwner());
        assertThat(historyCount("evt-legacy")).isEqualTo(3);
    }
}
