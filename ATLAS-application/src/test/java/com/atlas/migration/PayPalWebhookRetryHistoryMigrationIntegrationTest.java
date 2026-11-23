package com.atlas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.persistence.projection.PollResult;
import com.atlas.application.repository.PayPalWebhookRetryRepository;
import com.atlas.application.persistence.projection.ClaimedRetry;
import com.atlas.application.service.PayPalWebhookRetryWorker;
import com.atlas.application.service.PayPalWebhookService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
    void transientFailuresAreRescheduledAtSpreadOutTimes() throws IOException {
        // 같은 시각에 실패한 이벤트가 모두 같은 시각에 재시도되면 공급자가 복구되는 순간 한꺼번에 몰린다.
        // 시도 5의 기본 지연은 16초이고, 재시도 시각은 8~16초 사이로 흩어져야 한다.
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        Set<Long> offsets = new HashSet<>();
        for (int index = 0; index < 16; index++) {
            String eventId = "evt-spread-" + index;
            insertQueueRow(eventId, 4);
            var claimed = repository.claimDue().get(0);
            assertThat(claimed.attempt()).isEqualTo(5);
            assertThat(repository.reschedule(eventId, claimed.attempt(), claimed.leaseOwner(),
                    PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR, "provider unavailable")).isTrue();
            Double delay = jdbc.queryForObject("""
                    SELECT EXTRACT(EPOCH FROM (next_attempt_at - clock_timestamp()))
                    FROM paypal_webhook_retry WHERE event_id = ?
                    """, Double.class, eventId);
            assertThat(delay).isBetween(7.0, 16.5);
            offsets.add(Math.round(delay));
        }

        assertThat(offsets).hasSizeGreaterThanOrEqualTo(3);
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
    void concurrentClaimersDoNotReceiveTheSameDueWebhookRetry() throws Exception {
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        insertQueueRow("evt-concurrent-claim", 0);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService claimers = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimedRetry> firstClaim = claimers.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                return transaction.execute(status -> {
                    ClaimedRetry claimed = repository.claimDue().get(0);
                    firstClaimed.countDown();
                    try {
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("first claim was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return claimed;
                });
            });
            assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstClaim.isDone()).isFalse();

            Future<List<ClaimedRetry>> secondClaim = claimers.submit(repository::claimDue);
            assertThat(secondClaim.get(5, TimeUnit.SECONDS)).isEmpty();
            releaseFirst.countDown();

            ClaimedRetry claimed = firstClaim.get(5, TimeUnit.SECONDS);
            assertThat(claimed.attempt()).isEqualTo(1);
            assertThat(historyCount("evt-concurrent-claim")).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT attempt_count FROM paypal_webhook_retry WHERE event_id = ?",
                    Integer.class, "evt-concurrent-claim")).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            claimers.shutdownNow();
            assertThat(claimers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reaperBoundsEachPollAndDrainsExpiredMaxAttemptRows() throws IOException {
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        apply("V42__paypal_webhook_retry_reaper_index.sql");
        int rowCount = 11;
        for (int i = 0; i < rowCount; i++) {
            String eventId = "evt-reaper-" + i;
            insertQueueRow(eventId, 0);
            jdbc.update("UPDATE paypal_webhook_retry SET attempt_count = 8, lease_until = "
                    + "CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE event_id = ?", eventId);
        }

        List<String> plan = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            jdbc.execute("SET LOCAL enable_sort = off");
            return jdbc.queryForList("EXPLAIN SELECT event_id FROM paypal_webhook_retry "
                    + "WHERE state = 'PENDING' AND attempt_count = 8 "
                    + "AND (lease_until IS NULL OR lease_until <= clock_timestamp()) "
                    + "ORDER BY lease_until, event_id LIMIT 8", String.class);
        });
        assertThat(plan).anyMatch(line -> line.contains("paypal_webhook_retry_reaper_idx"));

        PollResult first = repository.pollDue();
        assertThat(first.reapedCount()).isEqualTo(8);
        assertThat(first.claimed()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'EXHAUSTED'",
                Integer.class)).isEqualTo(8);

        PollResult second = repository.pollDue();
        assertThat(second.reapedCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE state = 'EXHAUSTED'",
                Integer.class)).isEqualTo(rowCount);
        assertThat(repository.pollDue().reapedCount()).isZero();
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

    @Test
    void workerRecreationAcknowledgesDuplicateFromPersistentRetryQueue() throws IOException {
        apply("V33__paypal_webhook_retry_attempt_history.sql");
        insertQueueRow("evt-worker-recreation", 0);

        ClaimedRetry initial = repository.claimDue().get(0);
        assertThat(initial.attempt()).isEqualTo(1);
        assertThat(repository.reschedule("evt-worker-recreation", initial.attempt(), initial.leaseOwner(),
                PayPalWebhookRetryRepository.RetryFailure.TRANSIENT_ERROR,
                "initial transient failure")).isTrue();
        assertThat(historyCount("evt-worker-recreation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?",
                Integer.class, "evt-worker-recreation")).isEqualTo(1);

        jdbc.update("UPDATE paypal_webhook_retry SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE event_id = ?",
                "evt-worker-recreation");

        PayPalWebhookRetryRepository recreatedRepository = new PayPalWebhookRetryRepository(jdbc);
        PaymentService payments = Mockito.mock(PaymentService.class);
        Mockito.doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        }).when(payments).withPayPalWebhookOwnerLock(Mockito.any(), Mockito.any());
        Mockito.when(payments.processVerifiedPayPalWebhook(Mockito.any(PaymentWebhookEventResponse.class)))
                .thenReturn(new PaymentWebhookResponse(true, false, null, null, null, false, false));
        PayPalWebhookService recreatedWebhookService = new PayPalWebhookService(
                new DataSourceTransactionManager(dataSource), payments,
                Mockito.mock(OrderService.class), Mockito.mock(CheckoutPaymentService.class),
                Mockito.mock(OrderRefundService.class), recreatedRepository);
        PayPalWebhookRetryWorker recreatedWorker = new PayPalWebhookRetryWorker(
                recreatedRepository, recreatedWebhookService, new SimpleMeterRegistry());

        recreatedWorker.retryDueEvents();

        Mockito.verify(payments).processVerifiedPayPalWebhook(Mockito.any(PaymentWebhookEventResponse.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE event_id = ?",
                Integer.class, "evt-worker-recreation")).isZero();
        assertThat(historyCount("evt-worker-recreation")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM paypal_webhook_retry_attempt_history
                WHERE event_id = ? ORDER BY attempt_number DESC LIMIT 1
                """, String.class, "evt-worker-recreation")).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history
                WHERE event_id = ? AND outcome = 'TRANSIENT_ERROR'
                """, Integer.class, "evt-worker-recreation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM paypal_webhook_retry_attempt_history
                WHERE event_id = ? AND outcome = 'PROCESSED'
                """, Integer.class, "evt-worker-recreation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_number FROM paypal_webhook_retry_attempt_history
                WHERE event_id = ? AND outcome = 'PROCESSED'
                """, Integer.class, "evt-worker-recreation")).isEqualTo(2);
    }
}
