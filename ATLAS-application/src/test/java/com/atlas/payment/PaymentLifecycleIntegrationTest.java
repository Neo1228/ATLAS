package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.provider.fake.FakePsp;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class PaymentLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private PaymentService payments;
    @Autowired private FakePsp fakePsp;

    @Test
    void attemptsAreIdempotentAndUnknownPhasesRecoverOnSameAttempt() {
        UUID successOrder = UUID.randomUUID();
        PaymentResponse captured = payments.submitInitial(successOrder);
        PaymentResponse repeated = payments.submitInitial(successOrder);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(repeated).isEqualTo(captured);

        UUID pendingOrder = UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.PENDING, FakePspResult.AUTHORIZED);
        PaymentResponse pending = payments.submitInitial(pendingOrder);
        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(pending.authorizationActive()).isTrue();
        assertThat(payments.voidAuthorization(pendingOrder, "pending-void-" + pendingOrder)).isEqualTo(pending);
        assertThat(payments.submitInitial(pendingOrder).attemptId()).isEqualTo(pending.attemptId());
        PaymentResponse recovered = payments.reconcile(pendingOrder, "pending-reconcile-" + pendingOrder);
        assertThat(recovered.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.attemptId()).isEqualTo(pending.attemptId());

        UUID declineOrder = UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.DECLINED);
        PaymentResponse declined = payments.submitInitial(declineOrder);
        assertThat(declined.state()).isEqualTo(PaymentState.DECLINED);
        assertThat(declined.authorizationActive()).isFalse();

        UUID voidOrder = UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePspResult.DECLINED);
        fakePsp.enqueue(PaymentPhase.VOID, FakePspResult.PENDING, FakePspResult.UNKNOWN, FakePspResult.VOIDED);
        PaymentResponse captureDeclined = payments.submitInitial(voidOrder);
        assertThat(captureDeclined.state()).isEqualTo(PaymentState.DECLINED);
        assertThat(captureDeclined.authorizationActive()).isTrue();

        PaymentResponse voidPending = payments.voidAuthorization(voidOrder, "void-start-" + voidOrder);
        assertThat(voidPending.state()).isEqualTo(PaymentState.VOID_PENDING);
        assertThat(voidPending.authorizationActive()).isTrue();
        PaymentResponse voidUnknown = payments.reconcile(voidOrder, "void-reconcile-unknown-" + voidOrder);
        assertThat(voidUnknown.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(voidUnknown.authorizationActive()).isTrue();
        PaymentResponse voided = payments.reconcile(voidOrder, "void-reconcile-done-" + voidOrder);
        assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(voided.authorizationActive()).isFalse();
        UUID captureRecoveryOrder = UUID.randomUUID();
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePspResult.PENDING,
                FakePspResult.UNKNOWN, FakePspResult.CAPTURED);
        PaymentResponse capturePending = payments.submitInitial(captureRecoveryOrder);
        assertThat(capturePending.state()).isEqualTo(PaymentState.CAPTURE_PENDING);
        assertThat(capturePending.authorizationActive()).isTrue();
        assertThat(payments.voidAuthorization(captureRecoveryOrder, "capture-void-" + captureRecoveryOrder))
                .isEqualTo(capturePending);
        PaymentResponse captureUnknown = payments.reconcile(captureRecoveryOrder, "capture-reconcile-unknown-" + captureRecoveryOrder);
        assertThat(captureUnknown.state()).isEqualTo(PaymentState.UNKNOWN);
        PaymentResponse captureRecovered = payments.reconcile(captureRecoveryOrder, "capture-reconcile-done-" + captureRecoveryOrder);
        assertThat(captureRecovered.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captureRecovered.attemptId()).isEqualTo(capturePending.attemptId());

        assertThat(payments.find(successOrder)).isEqualTo(captured);
        assertThat(payments.find(voidOrder)).isEqualTo(voided);
        assertThat(payments.find(captureRecoveryOrder)).isEqualTo(captureRecovered);
    }
}
