package com.atlas.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.atlas.payment.PayPalIntegrationTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 두 replica가 동시에 만료 sweep을 시작할 때 한 쪽만 후보 조회를 수행하는지 검증합니다.
 *
 * <p>실제 서비스 대신 advisory lock만 직접 확인합니다. 서비스 전체를 띄우면 이 테스트가
 * 검증하려는 성질(한 tick에 한 replica만 sweep)이 다른 조건에 묻히기 때문입니다.
 */
@Testcontainers
@SpringBootTest
class JdbcExpirySweepLockRepositoryTest extends PayPalIntegrationTestSupport {
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
        // Keep background sweeps out of this test; it asserts lock behaviour only.
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired JdbcExpirySweepLockRepository locks;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void onlyOneReplicaAcquiresTheSameWorkflowSweep() throws Exception {
        String workflow = "orders-test-" + UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var holder = new TransactionTemplate(transactionManager);

        Thread first = new Thread(() -> holder.executeWithoutResult(status -> {
            assertThat(locks.tryAcquire(workflow)).isTrue();
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        first.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // Second replica: different transaction, same workflow -> must be refused.
        Boolean second = new TransactionTemplate(transactionManager)
                .execute((TransactionCallback<Boolean>)
                        status -> locks.tryAcquire(workflow));
        assertThat(second).isEqualTo(Boolean.FALSE);

        release.countDown();
        first.join(5_000);
        // After the holder commits, the lock is free again.
        Boolean after = new TransactionTemplate(transactionManager)
                .execute((TransactionCallback<Boolean>)
                        status -> locks.tryAcquire(workflow));
        assertThat(after).isEqualTo(Boolean.TRUE);
    }

    @Test
    void differentWorkflowsDoNotBlockEachOther() {
        String a = "orders-test-" + UUID.randomUUID();
        String b = "checkouts-test-" + UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);
        Boolean result = tx.execute((TransactionCallback<Boolean>)
                status -> locks.tryAcquire(a) && locks.tryAcquire(b));
        assertThat(result).isEqualTo(Boolean.TRUE);
    }

    @Test
    void lockIsReleasedWithTheTransactionNotWithTheConnection() {
        String workflow = "orders-test-" + UUID.randomUUID();
        var tx = new TransactionTemplate(transactionManager);
        assertThat(tx.execute((TransactionCallback<Boolean>)
                status -> locks.tryAcquire(workflow))).isEqualTo(Boolean.TRUE);
        // The transaction committed above; a fresh transaction must acquire again even
        // on the same pooled connection.
        assertThat(tx.execute((TransactionCallback<Boolean>)
                status -> locks.tryAcquire(workflow))).isEqualTo(Boolean.TRUE);
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }
}
