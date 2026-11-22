package com.atlas.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.PayPalIntegrationTestSupport;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 운영과 같은 조건, 즉 transaction 밖에서 호출했을 때 sweep이 한 번에 하나만 실행되는지 검증합니다.
 *
 * <p>이전 구현은 transaction 범위 advisory lock을 transaction 밖에서 잡아 문장이 끝나는 즉시 풀렸고,
 * transaction 안에서 호출하는 테스트만 통과했습니다.
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
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired JdbcExpirySweepLockRepository locks;

    @Test
    void concurrentSweepOfTheSameWorkflowIsSkippedWhileAnotherRunsOutsideAnyTransaction() throws Exception {
        String workflow = "orders-test-" + UUID.randomUUID();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = new Thread(() -> locks.runExclusive(workflow, () -> {
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }));
        first.start();
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        Optional<Integer> skipped = locks.runExclusive(workflow, () -> 2);
        Optional<Integer> otherWorkflow = locks.runExclusive(workflow + "-other", () -> 3);

        release.countDown();
        first.join(10_000);
        assertThat(skipped).isEmpty();
        assertThat(otherWorkflow).contains(3);
    }

    @Test
    void lockIsReleasedAfterTheSweepEvenWhenItFails() {
        String workflow = "orders-test-" + UUID.randomUUID();

        assertThatThrownBy(() -> locks.runExclusive(workflow, () -> {
            throw new IllegalStateException("sweep failed");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("sweep failed");

        assertThat(locks.runExclusive(workflow, () -> 7)).contains(7);
    }
}
