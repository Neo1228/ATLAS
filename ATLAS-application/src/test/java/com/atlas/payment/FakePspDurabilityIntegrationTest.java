package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.provider.fake.FakePsp;
import com.atlas.payment.repository.FakePspRepository;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void sameKeyReplaysWithoutAdvancingAndNewKeyContinuesAfterAdapterRecreation() {
        String requestId = "durability-test:" + UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.PENDING,
                FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        assertThat(fakePsp.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-1"))
                .isEqualTo(FakePsp.Result.PENDING);
        assertThat(fakePsp.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-1"))
                .isEqualTo(FakePsp.Result.PENDING);

        FakePsp recreated = new FakePsp(repository);
        assertThat(recreated.perform(PaymentPhase.AUTHORIZATION, requestId, "poll-2"))
                .isEqualTo(FakePsp.Result.UNKNOWN);
        assertThat(new FakePsp(repository).perform(PaymentPhase.AUTHORIZATION, requestId, "poll-3"))
                .isEqualTo(FakePsp.Result.AUTHORIZED);
    }

    @Test
    void committedProviderResultSurvivesCallerFailureAndConcurrentSameKeyCalls() throws Exception {
        String requestId = "failure-test:" + UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePsp.Result.PENDING, FakePsp.Result.CAPTURED);
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
            FakePsp.Result committed = fakePsp.perform(PaymentPhase.CAPTURE, requestId, "poll-retry");
            assertThat(committed).isEqualTo(FakePsp.Result.PENDING);
            throw new IllegalStateException("simulate downstream state update failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-retry"))
                .isEqualTo(FakePsp.Result.PENDING);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() ->
                    new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-concurrent"));
            var second = executor.submit(() ->
                    new FakePsp(repository).perform(PaymentPhase.CAPTURE, requestId, "poll-concurrent"));
            assertThat(first.get()).isEqualTo(FakePsp.Result.CAPTURED);
            assertThat(second.get()).isEqualTo(FakePsp.Result.CAPTURED);
        } finally {
            executor.shutdownNow();
        }
    }
}
