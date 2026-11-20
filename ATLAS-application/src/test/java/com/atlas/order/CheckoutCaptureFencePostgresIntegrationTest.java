package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.PaymentProvider;
import com.atlas.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class CheckoutCaptureFencePostgresIntegrationTest extends PayPalIntegrationTestSupport {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.payment.recovery.worker.enabled", () -> false);
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean PaymentProvider provider;

    @Test
    void fenceAndCancellationRollBackTogether() {
        UUID checkout = authorizedAttempt();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT checkout_id FROM buyer_checkout WHERE checkout_id = ? FOR UPDATE", UUID.class, checkout);
            payments.prohibitNewCheckoutCapture(checkout);
            jdbc.update("UPDATE buyer_checkout SET state = 'CANCEL_PENDING' WHERE checkout_id = ?", checkout);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT capture_prohibited FROM payment_attempt WHERE checkout_id = ?", Boolean.class, checkout)).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM buyer_checkout WHERE checkout_id = ?", String.class, checkout)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void committedFenceBeatsConcurrentCaptureReservation() throws Exception {
        UUID checkout = authorizedAttempt();
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT checkout_id FROM buyer_checkout WHERE checkout_id = ? FOR UPDATE", UUID.class, checkout);
                payments.prohibitNewCheckoutCapture(checkout);
                jdbc.update("UPDATE buyer_checkout SET state = 'CANCEL_PENDING' WHERE checkout_id = ?", checkout);
                var future = executor.submit(() -> {
                    started.countDown();
                    return payments.reconcileCheckout(checkout, "racing-capture");
                });
                awaitRowLockWait(started, future);
                return future;
            });
            assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.AUTHORIZED);
            verify(provider, never()).perform(any());
        }
    }

    @Test
    void orderOwnedFenceRollsBackWithItsTransaction() {
        UUID order = authorizedOrderAttempt();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            payments.prohibitNewOrderCapture(order);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT capture_prohibited FROM payment_attempt WHERE order_id = ?",
                Boolean.class, order)).isFalse();
    }

    @Test
    void committedOrderFenceBeatsConcurrentCaptureReservation() throws Exception {
        UUID order = authorizedOrderAttempt();
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = new TransactionTemplate(manager).execute(status -> {
                payments.prohibitNewOrderCapture(order);
                var future = executor.submit(() -> {
                    started.countDown();
                    return payments.reconcile(order, "racing-order-capture");
                });
                awaitRowLockWait(started, future);
                return future;
            });
            assertThat(result.get(10, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.AUTHORIZED);
            verify(provider, never()).perform(any());
        }
    }

    /**
     * 경쟁 스레드가 payment_attempt 행 잠금을 실제로 기다릴 때까지 대기한다.
     * pg_stat_activity 는 트랜잭션 안에서 처음 읽은 스냅샷을 재사용하므로 매번 비운다.
     */
    private void awaitRowLockWait(CountDownLatch started, Future<?> racer) {
        try {
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("reconcile did not start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                jdbc.queryForObject("SELECT pg_stat_clear_snapshot()", Object.class);
                Integer waiters = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                                + "AND wait_event_type = 'Lock' AND lower(query) LIKE '%payment_attempt%'",
                        Integer.class);
                if (waiters != null && waiters > 0) return;
                if (racer.isDone()) throw new AssertionError("reconcile finished without blocking: " + racer.get());
                Thread.sleep(20);
            }
            throw new AssertionError("reconcile did not block on the payment row");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException e) {
            throw new AssertionError("reconcile failed before blocking", e.getCause());
        }
    }

    // 캠페인 주문처럼 주문이 직접 소유한 결제 시도. payment_attempt.order_id 에는 FK 가 없다.
    private UUID authorizedOrderAttempt() {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_attempt (attempt_id, order_id, state, payment_phase, authorization_active, total_won) "
                + "VALUES (?, ?, 'AUTHORIZED', 'CAPTURE', true, 100)", UUID.randomUUID(), order);
        return order;
    }

    // 별도 JDBC 트랜잭션과 실제 행 잠금을 사용하지만 Spring 서비스 인스턴스는 하나다.
    private UUID authorizedAttempt() {
        UUID checkout = UUID.randomUUID();
        jdbc.update("INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won, created_at, updated_at) VALUES (?, ?, ?, 'PAYMENT_PENDING', 100, now(), now())",
                checkout, "buyer", checkout.toString());
        payments.prepareCheckout(checkout, BigDecimal.valueOf(100));
        jdbc.update("UPDATE payment_attempt SET state = 'AUTHORIZED', payment_phase = 'CAPTURE', authorization_active = true WHERE checkout_id = ?", checkout);
        return checkout;
    }
}
