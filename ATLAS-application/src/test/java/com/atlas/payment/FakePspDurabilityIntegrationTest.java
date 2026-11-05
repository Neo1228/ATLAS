package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.provider.fake.FakePsp;
import com.atlas.payment.repository.FakePspRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class FakePspDurabilityIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired FakePsp fakePsp;
    @Autowired FakePspRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameKeyReplaysWithoutAdvancingAndNewKeyContinuesAfterAdapterRecreation() {
        String requestId = "durability-test:" + UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.PENDING,
                FakePspResult.UNKNOWN, FakePspResult.AUTHORIZED);

        assertThat(fakePsp.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-1"))
                .isEqualTo(FakePspResult.PENDING);
        assertThat(fakePsp.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-1"))
                .isEqualTo(FakePspResult.PENDING);

        FakePsp recreated = new FakePsp(repository);
        assertThat(recreated.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-2"))
                .isEqualTo(FakePspResult.UNKNOWN);
        assertThat(new FakePsp(repository).perform(PaymentPhase.AUTHORIZATION, requestId, "poll-3"))
                .isEqualTo(FakePspResult.AUTHORIZED);
    }

    @Test
    void committedProviderResultSurvivesCallerFailureAndConcurrentSameKeyCalls() throws Exception {
        String requestId = "failure-test:" + UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePspResult.PENDING, FakePspResult.CAPTURED);
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            FakePspResult committed = fakePsp.perform(PaymentPhase.CAPTURE, requestId, "poll-retry");
            assertThat(committed).isEqualTo(FakePspResult.PENDING);
            throw new IllegalStateException("simulate downstream state update failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-retry"))
                .isEqualTo(FakePspResult.PENDING);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() ->
                    new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-concurrent"));
            var second = executor.submit(() ->
                    new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-concurrent"));
            assertThat(first.get()).isEqualTo(FakePspResult.CAPTURED);
            assertThat(second.get()).isEqualTo(FakePspResult.CAPTURED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentFirstEnqueueAndPerformSerializeWithoutLosingScript() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        String firstRequest = "first-race:" + UUID.randomUUID();
        jdbc.update("DELETE FROM payment_fake_psp_script WHERE payment_phase = ?", PaymentPhase.VOID.name());
        try {
            var enqueue = executor.submit(() -> {
                ready.countDown();
                start.await();
                fakePsp.enqueue(PaymentPhase.VOID, FakePspResult.UNKNOWN);
                return null;
            });
            var perform = executor.submit(() -> {
                ready.countDown();
                start.await();
                return fakePsp.perform(PaymentPhase.VOID, firstRequest, "first");
            });
            ready.await();
            start.countDown();
            enqueue.get();
            FakePspResult first = perform.get();
            FakePspResult next = fakePsp.perform(PaymentPhase.VOID, "later:" + UUID.randomUUID(), "next");
            assertThat(first == FakePspResult.UNKNOWN || first == FakePspResult.VOIDED).isTrue();
            assertThat((first == FakePspResult.UNKNOWN ? 1 : 0)
                    + (next == FakePspResult.UNKNOWN ? 1 : 0)).isEqualTo(1);
            assertThat(next == FakePspResult.UNKNOWN || next == FakePspResult.VOIDED).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedEnqueuePreservesAppendOrder() {
        jdbc.update("DELETE FROM payment_fake_psp_script WHERE payment_phase = ?", PaymentPhase.VOID.name());
        fakePsp.enqueue(PaymentPhase.VOID, FakePspResult.PENDING);
        fakePsp.enqueue(PaymentPhase.VOID, FakePspResult.UNKNOWN, FakePspResult.VOIDED);
        String request = "append:" + UUID.randomUUID();
        assertThat(fakePsp.perform(PaymentPhase.VOID, request, "one")).isEqualTo(FakePspResult.PENDING);
        assertThat(fakePsp.perform(PaymentPhase.VOID, request, "two")).isEqualTo(FakePspResult.UNKNOWN);
        assertThat(fakePsp.perform(PaymentPhase.VOID, request, "three")).isEqualTo(FakePspResult.VOIDED);
    }
}
