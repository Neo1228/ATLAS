package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 요청 스레드의 PayPal 동시 호출 상한(ADR-0098)이 결제 상태를 어긋나게 하지 않는지 실제 PostgreSQL과 실제
 * {@code PayPalRestClient}로 확인한다. 상한은 1로 둔다. 호출 하나를 PayPal stub에서 붙잡아 상한을 쥔 상태를
 * 만들고, 서블릿 요청 스레드는 {@code RequestContextHolder}를 걸어 흉내 낸다.
 */
@Testcontainers
@SpringBootTest
class PayPalBulkheadIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String CREATE_ORDER_CALL = "POST /v2/checkout/orders";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(17_200);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.initial-delay", () -> "PT0S");
        properties.add("atlas.payment.paypal.bulkhead.max-concurrent-calls", () -> "1");
    }

    @Autowired
    private PaymentService payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private ExecutorService threads;

    @BeforeEach
    void startThreads() {
        threads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void requestThreadOverTheLimitIsRejectedBeforeSendingAndItsPaymentCanBeRetried() throws Exception {
        UUID heldOrder = UUID.randomUUID();
        UUID rejectedOrder = UUID.randomUUID();
        double rejectedBefore = rejectedCount();
        Hold hold = holdNextCreateOrder();
        Future<PaymentResponse> held = threads.submit(() -> asRequestThread(
                () -> payments.submitInitial(heldOrder, AMOUNT)));
        assertThat(hold.arrived().await(10, TimeUnit.SECONDS)).isTrue();

        PaymentResponse rejected = asRequestThread(() -> payments.submitInitial(rejectedOrder, AMOUNT));

        // 거절은 PayPal로 아무것도 보내기 전에 일어났다: 상한을 쥔 호출 하나만 stub에 닿았다.
        assertThat(providerCalls().getOrDefault(CREATE_ORDER_CALL, 0)).isEqualTo(1);
        assertThat(rejectedCount() - rejectedBefore).isEqualTo(1.0);
        // 기존 장애 처리와 같다: 응답은 결과 불명(UNKNOWN)이고 같은 요청의 재시도와 복구가 이어 받는다. 주문 ID는 아직 없다.
        assertThat(rejected.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(payments.find(rejectedOrder).state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(providerOrderIdOf(rejectedOrder)).isNull();

        hold.release().countDown();
        assertThat(held.get(10, TimeUnit.SECONDS).providerOrderId()).isNotNull();

        PaymentResponse retried = asRequestThread(() -> payments.reconcile(rejectedOrder, "retry-after-busy"));
        assertThat(retried.providerOrderId()).isNotNull().isNotEqualTo(held.get().providerOrderId());
        assertThat(providerCalls().getOrDefault(CREATE_ORDER_CALL, 0)).isEqualTo(2);
    }

    @Test
    void rejectedCaptureSendsNothingAndTheRetryCapturesExactlyOnce() throws Exception {
        UUID authorizedOrder = UUID.randomUUID();
        PaymentResponse pending = asRequestThread(() -> payments.submitInitial(authorizedOrder, AMOUNT));
        PaymentResponse authorized = asRequestThread(
                () -> payments.completeApproval(authorizedOrder, pending.providerOrderId()));
        assertThat(authorized.state()).isEqualTo(PaymentState.AUTHORIZED);
        Hold hold = holdNextCreateOrder();
        Future<PaymentResponse> held = threads.submit(() -> asRequestThread(
                () -> payments.submitInitial(UUID.randomUUID(), AMOUNT)));
        assertThat(hold.arrived().await(10, TimeUnit.SECONDS)).isTrue();

        PaymentResponse rejected = asRequestThread(() -> payments.reconcile(authorizedOrder, "capture-while-busy"));

        // 돈이 움직이는 호출이다. 거절은 보내기 전에 일어나 PayPal capture가 없고, 결제는 복구가 이어 받는 UNKNOWN이다.
        assertThat(rejected.state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(captureRequestCount()).isZero();
        hold.release().countDown();
        held.get(10, TimeUnit.SECONDS);

        PaymentResponse retried = asRequestThread(() -> payments.reconcile(authorizedOrder, "capture-retry"));
        assertThat(retried.state()).isEqualTo(PaymentState.CAPTURED);
        assertThat(captureRequestCount()).isEqualTo(1);
    }

    @Test
    void callsFromNonRequestThreadsAreNotLimitedWhileRequestThreadsHoldTheLimit() throws Exception {
        UUID heldOrder = UUID.randomUUID();
        UUID workerOrder = UUID.randomUUID();
        double rejectedBefore = rejectedCount();
        Hold hold = holdNextCreateOrder();
        Future<PaymentResponse> held = threads.submit(() -> asRequestThread(
                () -> payments.submitInitial(heldOrder, AMOUNT)));
        assertThat(hold.arrived().await(10, TimeUnit.SECONDS)).isTrue();

        // 복구 worker와 같은 조건: 서블릿 요청 밖의 스레드. 상한을 쥔 호출이 있어도 PayPal로 나간다.
        PaymentResponse fromWorker = threads.submit(() -> payments.submitInitial(workerOrder, AMOUNT))
                .get(10, TimeUnit.SECONDS);

        assertThat(fromWorker.providerOrderId()).isNotNull();
        assertThat(providerCalls().getOrDefault(CREATE_ORDER_CALL, 0)).isEqualTo(2);
        assertThat(rejectedCount() - rejectedBefore).isZero();
        hold.release().countDown();
        try {
            held.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException unexpected) {
            throw new AssertionError("held request thread call must complete once released", unexpected);
        }
    }

    private double rejectedCount() {
        var counter = meters.find("atlas.payment.paypal.bulkhead.rejected").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String providerOrderIdOf(UUID orderId) {
        return jdbc.queryForObject("SELECT provider_order_id FROM payment_attempt WHERE order_id = ?",
                String.class, orderId);
    }

    private static <T> T asRequestThread(Callable<T> action) throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            return action.call();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
