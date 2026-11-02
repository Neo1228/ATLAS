package com.atlas.payment.internal;

import com.atlas.payment.PaymentApi;
import com.atlas.payment.PaymentState;
import com.atlas.payment.PaymentView;

import static org.assertj.core.api.Assertions.assertThat;

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
class PaymentApiTest {

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

    @Autowired private PaymentApi payments;
    @Autowired private FakePsp fakePsp;

    @Test
    void attemptsAreIdempotentAndUnknownOperationsRecoverOnSameAttempt() {
        UUID successOrder = UUID.randomUUID();
        PaymentView captured = payments.submitInitial(successOrder);
        PaymentView repeated = payments.submitInitial(successOrder);
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(repeated).isEqualTo(captured);

        UUID pendingOrder = UUID.randomUUID();
        fakePsp.enqueue(FakePsp.Operation.AUTHORIZATION, FakePsp.Result.PENDING, FakePsp.Result.AUTHORIZED);
        PaymentView pending = payments.submitInitial(pendingOrder);
        assertThat(pending.state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(pending.authorizationActive()).isTrue();
        assertThat(payments.voidAuthorization(pendingOrder)).isEqualTo(pending);
        assertThat(payments.submitInitial(pendingOrder).attemptId()).isEqualTo(pending.attemptId());
        PaymentView recovered = payments.reconcile(pendingOrder);
        assertThat(recovered.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.attemptId()).isEqualTo(pending.attemptId());

        UUID declineOrder = UUID.randomUUID();
        fakePsp.enqueue(FakePsp.Operation.AUTHORIZATION, FakePsp.Result.DECLINED);
        PaymentView declined = payments.submitInitial(declineOrder);
        assertThat(declined.state()).isEqualTo(PaymentState.DECLINED);
        assertThat(declined.authorizationActive()).isFalse();

        UUID voidOrder = UUID.randomUUID();
        fakePsp.enqueue(FakePsp.Operation.CAPTURE, FakePsp.Result.DECLINED);
        fakePsp.enqueue(FakePsp.Operation.VOID, FakePsp.Result.PENDING, FakePsp.Result.UNKNOWN, FakePsp.Result.VOIDED);
        PaymentView captureDeclined = payments.submitInitial(voidOrder);
        assertThat(captureDeclined.state()).isEqualTo(PaymentState.DECLINED);
        assertThat(captureDeclined.authorizationActive()).isTrue();

        PaymentView voidPending = payments.voidAuthorization(voidOrder);
        assertThat(voidPending.state()).isEqualTo(PaymentState.VOID_PENDING);
        assertThat(voidPending.authorizationActive()).isTrue();
        PaymentView voidUnknown = payments.reconcile(voidOrder);
        assertThat(voidUnknown.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(voidUnknown.authorizationActive()).isTrue();
        PaymentView voided = payments.reconcile(voidOrder);
        assertThat(voided.state()).isEqualTo(PaymentState.VOIDED);
        assertThat(voided.authorizationActive()).isFalse();
        UUID captureRecoveryOrder = UUID.randomUUID();
        fakePsp.enqueue(FakePsp.Operation.CAPTURE, FakePsp.Result.PENDING,
                FakePsp.Result.UNKNOWN, FakePsp.Result.CAPTURED);
        PaymentView capturePending = payments.submitInitial(captureRecoveryOrder);
        assertThat(capturePending.state()).isEqualTo(PaymentState.CAPTURE_PENDING);
        assertThat(capturePending.authorizationActive()).isTrue();
        assertThat(payments.voidAuthorization(captureRecoveryOrder)).isEqualTo(capturePending);
        PaymentView captureUnknown = payments.reconcile(captureRecoveryOrder);
        assertThat(captureUnknown.state()).isEqualTo(PaymentState.UNKNOWN);
        PaymentView captureRecovered = payments.reconcile(captureRecoveryOrder);
        assertThat(captureRecovered.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captureRecovered.attemptId()).isEqualTo(capturePending.attemptId());

        assertThat(payments.find(successOrder)).isEqualTo(captured);
        assertThat(payments.find(voidOrder)).isEqualTo(voided);
        assertThat(payments.find(captureRecoveryOrder)).isEqualTo(captureRecovered);
    }
}
