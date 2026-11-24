package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.repository.PaymentRefundRepository;
import com.atlas.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 결제에 동시 환불 요청이 몰릴 때 DB 가 초과 환불을 막는지 확인한다.
 *
 * <p>프로덕션 replica 가 2대 이상이면 {@code OwnerLockRegistry}(JVM 전역)가 두 요청을
 * 직렬화하지 못한다. 이 테스트는 그 상황을 가정하고, DB 계층만으로 정합성이 유지되는지 본다.
 *
 * <p>주의: 단일 JVM 이므로 인메모리 락이 실제로는 동작한다. 다만 두 요청이 서로 다른
 * idempotency key 를 쓰면 락을 지나 서로 다른 환불로 진행되므로, 금액 상한 검증이
 * DB/transaction 수준에서 유효한지 관측할 수 있다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class ConcurrentRefundGuardTest extends PayPalIntegrationTestSupport {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
    }

    @Autowired private PaymentService payments;
    @Autowired private PaymentRefundRepository refunds;

    @Test
    void concurrentRefundsWithDifferentKeysCannotExceedTheCapturedAmount() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = BigDecimal.valueOf(17_200);
        UUID attemptId = capturePayment(orderId, amount);

        int racers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(racers)) {
            for (int index = 0; index < racers; index++) {
                String key = "concurrent-refund-" + index + "-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        // 각 요청이 전체 금액을 환불하려 한다. 하나만 성공해야 한다.
                        return payments.refundOrder(orderId, key, amount.longValueExact());
                    } catch (RuntimeException rejected) {
                        return rejected;
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        long refundedTotal = refunds.refundedAmountForAttempt(attemptId);
        System.out.printf("CONCURRENT_REFUND captured_won=%d refunded_won=%d%n",
                amount.longValueExact(), refundedTotal);

        // 상한 검증: 성공한 환불의 합이 결제 금액을 넘지 않아야 한다.
        assertThat(refundedTotal)
                .as("sum of accepted refunds must not exceed the captured amount")
                .isLessThanOrEqualTo(amount.longValueExact());
    }

    private UUID capturePayment(UUID orderId, BigDecimal amount) {
        var pending = payments.submitInitial(orderId, amount);
        var authorized = payments.completeApproval(orderId, pending.providerOrderId());
        assertThat(authorized.state().name()).isEqualTo("AUTHORIZED");
        var captured = payments.reconcile(orderId, "capture-" + orderId);
        assertThat(captured.state().name()).isEqualTo("CAPTURED");
        return captured.attemptId();
    }
}
