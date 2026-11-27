package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.service.PaymentRecoveryWorker;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.service.PaymentRecoveryService;
import com.atlas.payment.service.PaymentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 결제 복구 재시도 예산을 운영 기본값 그대로 확인한다(EXP-117 H3, ADR-0106).
 *
 * <p>시간은 실제로 기다리지 않는다. worker가 기록한 재예약 지연만큼 이 job 행의 시각 열을 과거로 옮겨
 * 가상 시계를 만든다. 그래서 claim·재예약·창 판정은 운영 코드와 PostgreSQL 시각 그대로 돈다.
 */
@Testcontainers
@SpringBootTest
class PaymentRecoveryRetryBudgetIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String CREATE_ORDER_CALL = "POST /v2/checkout/orders";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("atlas.payment.recovery.initial-delay", () -> "PT0S");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentRecoveryService recovery;
    @Autowired
    private PaymentService payments;
    @Autowired
    private OrderService orders;
    @Autowired
    private CheckoutPaymentService checkouts;
    @Autowired
    private OrderRefundService refunds;
    @Autowired
    private CartCheckoutService cartCheckouts;
    @Autowired
    private CartService carts;
    @Autowired
    private SellerService sellers;
    @Autowired
    private ProductService products;

    private PaymentRecoveryWorker worker;
    private int maxAttempts;
    private int maxAbandonedClaims;
    private Duration maxAge;
    private Duration leaseDuration;

    /**
     * 운영 기본값은 main의 application.yml에 있다. 시험 classpath의 application.yml이 그 파일을 가리므로
     * 파일을 직접 읽고 자리표시자의 기본값으로 푼다(환경 변수가 없을 때 운영이 쓰는 값).
     */
    private static Environment shippedDefaults() {
        try {
            StandardEnvironment shipped = new StandardEnvironment();
            shipped.setConversionService(new ApplicationConversionService());
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("shipped",
                    new FileSystemResource("src/main/resources/application.yml"))) {
                shipped.getPropertySources().addFirst(source);
            }
            return shipped;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @BeforeEach
    void setUp() {
        Environment environment = shippedDefaults();
        jdbc.update("DELETE FROM payment_recovery_operator_action");
        jdbc.update("DELETE FROM payment_recovery_job");
        maxAttempts = environment.getRequiredProperty("atlas.payment.recovery.max-attempts", Integer.class);
        maxAbandonedClaims = environment.getRequiredProperty(
                "atlas.payment.recovery.max-abandoned-claims", Integer.class);
        maxAge = environment.getRequiredProperty("atlas.payment.recovery.max-age", Duration.class);
        leaseDuration = environment.getRequiredProperty("atlas.payment.recovery.lease-duration", Duration.class);
        // 운영 기본값(application.yml) 그대로의 worker.
        worker = new PaymentRecoveryWorker(recovery, payments, orders, checkouts, refunds,
                new SimpleMeterRegistry(),
                environment.getRequiredProperty("atlas.payment.recovery.batch-size", Integer.class),
                leaseDuration, maxAttempts, maxAbandonedClaims, maxAge,
                environment.getRequiredProperty("atlas.payment.recovery.base-backoff", Duration.class),
                environment.getRequiredProperty("atlas.payment.recovery.max-backoff", Duration.class));
    }

    /**
     * H3(a): 시도 한도 8에서는 PayPal 503이 약 83~116초(가상) 이어지면 job이 사람 손으로 넘어갔다(H1).
     * 이제 그보다 훨씬 긴 6시간 장애 동안 자동 복구에 머물고, PayPal이 돌아오면 다음 claim에서 진행한다.
     */
    @Test
    void providerOutageShorterThanTheWindowStaysAutomaticAndRecovers() {
        UUID attemptId = unknownCheckoutPayment();
        Outage outage = runOutage(attemptId, Duration.ofHours(6));
        System.out.printf("EXP117_H3A claims=%d virtual_seconds=%.1f status=%s%n",
                outage.claims(), outage.virtualMillis() / 1000.0, jobStatus(attemptId));
        assertThat(jobStatus(attemptId)).isEqualTo("PENDING");
        assertThat(outage.virtualMillis()).isGreaterThanOrEqualTo(Duration.ofHours(6).toMillis());

        // PayPal 회복: 거절을 더 넣지 않는다. 같은 Request-Id로 주문 생성이 성공해 구매자 승인 대기로 간다.
        worker.poll();
        assertThat(payments.findByAttemptId(attemptId).state()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(jobStatus(attemptId)).isEqualTo("WAITING_FOR_BUYER");
    }

    /** H3(a): 장애가 창보다 길면 시도 한도가 아니라 창(max-age)이 job을 사람 손으로 넘긴다. */
    @Test
    void providerOutageLongerThanTheWindowGoesToReviewAtTheWindow() {
        UUID attemptId = unknownCheckoutPayment();
        Outage outage = runOutage(attemptId, Duration.ofDays(3));
        Map<String, Object> job = job(attemptId);
        System.out.printf("EXP117_H3W claims=%d virtual_seconds=%.1f status=%s error=%s attempt_count=%s%n",
                outage.claims(), outage.virtualMillis() / 1000.0, job.get("status"), job.get("last_error_code"),
                job.get("attempt_count"));
        assertThat(job.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(outage.virtualMillis()).isGreaterThanOrEqualTo(maxAge.toMillis());
        assertThat(outage.claims()).isLessThan(maxAttempts);
    }

    /**
     * H3(b): claim한 인스턴스가 결과를 쓰기 전에 죽으면(lease 만료) 버려진 claim으로 센다. 같은 job을 잡을
     * 때마다 프로세스가 죽는 경우를 예전 시도 한도와 같은 8회에서 멈춘다.
     */
    @Test
    void abandonedClaimsStopAJobThatKillsItsWorker() {
        UUID attemptId = unknownCheckoutPayment();
        int abandoned = 0;
        while ("PENDING".equals(jobStatus(attemptId)) && abandoned < 400) {
            assertThat(recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge)).hasSize(1);
            abandoned++;
            // 인스턴스가 죽어 lease가 만료됐다. 살아 있는 worker의 poll 첫 단계(sweep)가 한도를 판정한다.
            jdbc.update("""
                    UPDATE payment_recovery_job
                    SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE payment_attempt_id = ?
                    """, attemptId);
            recovery.expireExhausted(maxAttempts, maxAbandonedClaims, maxAge);
        }
        Map<String, Object> job = job(attemptId);
        System.out.printf("EXP117_H3B abandoned_claims=%d status=%s error=%s%n",
                abandoned, job.get("status"), job.get("last_error_code"));
        assertThat(job.get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(job.get("last_error_code")).isEqualTo("ABANDONED_CLAIMS");
        assertThat(abandoned).isEqualTo(maxAbandonedClaims);
        assertThat(callCount(CREATE_ORDER_CALL)).as("the dead claims never reached PayPal").isEqualTo(1);

        // 운영자가 원인을 고치고 다시 올리면 두 계수가 0에서 시작한다. 버려진 claim 한 번으로 곧바로 멈추지 않는다.
        UUID jobId = (UUID) job.get("recovery_job_id");
        assertThat(recovery.requeueNeedsReview(jobId, "requeue-" + jobId, "operator", "fixed the crash", 3)).isPresent();
        assertThat(job(attemptId).get("abandoned_claim_count")).isEqualTo(0);
        assertThat(job(attemptId).get("attempt_count")).isEqualTo(0);
    }

    /** 결과를 기록한 재시도 뒤의 강제 종료 한 번은 버려진 claim 한 번일 뿐이다. 시도 수와 섞이지 않는다. */
    @Test
    void recordedRetriesDoNotCountAsAbandonedClaims() {
        UUID attemptId = unknownCheckoutPayment();
        runOutage(attemptId, Duration.ofHours(1));
        assertThat(recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge)).hasSize(1);
        jdbc.update("""
                UPDATE payment_recovery_job
                SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE payment_attempt_id = ?
                """, attemptId);
        recovery.expireExhausted(maxAttempts, maxAbandonedClaims, maxAge);
        assertThat(jobStatus(attemptId)).isEqualTo("PENDING");
        queueRejection("create-order", 503, "INTERNAL_SERVER_ERROR");
        worker.poll();
        assertThat(job(attemptId).get("abandoned_claim_count")).isEqualTo(1);
    }

    /**
     * 인스턴스가 여럿이면 lease는 죽지 않아도 만료된다(긴 GC 정지, DB와의 분할). 살아 있지만 느린 A가 lease를
     * 잃은 뒤 B가 다시 잡고, A가 나중에 결과를 쓰려 하면: 버려진 claim은 한 번만 세고 A의 결과는 lease 토큰에
     * 막혀 B의 기록을 덮지 않는다.
     */
    @Test
    void slowLiveWorkerIsCountedOnceAndItsLateResultIsFenced() {
        UUID attemptId = unknownCheckoutPayment();
        var slow = recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge).getFirst();
        jdbc.update("""
                UPDATE payment_recovery_job
                SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE payment_attempt_id = ?
                """, attemptId);
        // B: 살아 있는 다른 인스턴스의 poll. 만료된 lease를 다시 잡고 PayPal 503으로 재예약한다.
        queueRejection("create-order", 503, "INTERNAL_SERVER_ERROR");
        worker.poll();
        Map<String, Object> afterTakeover = job(attemptId);
        assertThat(afterTakeover.get("status")).isEqualTo("PENDING");
        assertThat(afterTakeover.get("abandoned_claim_count")).isEqualTo(1);
        assertThat(afterTakeover.get("attempt_count")).isEqualTo(2);

        // A가 깨어나 결과를 쓰려 한다. 재예약·완료·검토 전환 모두 토큰이 달라 0행이다.
        assertThat(recovery.reschedule(slow, Duration.ZERO, maxAttempts, maxAge, "SLOW_WORKER")).isFalse();
        assertThat(recovery.complete(slow)).isFalse();
        Map<String, Object> afterLateWrite = job(attemptId);
        System.out.printf("EXP117_SLOW abandoned=%s attempts=%s status=%s error=%s%n",
                afterLateWrite.get("abandoned_claim_count"), afterLateWrite.get("attempt_count"),
                afterLateWrite.get("status"), afterLateWrite.get("last_error_code"));
        assertThat(afterLateWrite.get("abandoned_claim_count")).isEqualTo(1);
        assertThat(afterLateWrite.get("attempt_count")).isEqualTo(2);
        assertThat(afterLateWrite.get("last_error_code")).isEqualTo(afterTakeover.get("last_error_code"));
        assertThat(afterLateWrite.get("available_at")).isEqualTo(afterTakeover.get("available_at"));
    }

    /** lease가 만료됐지만 아무도 다시 잡기 전에 느린 worker가 결과를 쓰면 버려진 claim이 아니다. */
    @Test
    void slowWorkerThatFinishesBeforeAnyTakeoverIsNotAbandoned() {
        UUID attemptId = unknownCheckoutPayment();
        var slow = recovery.claimNextDue(1, UUID.randomUUID(), leaseDuration, maxAttempts, maxAge).getFirst();
        jdbc.update("""
                UPDATE payment_recovery_job
                SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE payment_attempt_id = ?
                """, attemptId);
        assertThat(recovery.reschedule(slow, Duration.ZERO, maxAttempts, maxAge, "SLOW_WORKER")).isTrue();
        queueRejection("create-order", 503, "INTERNAL_SERVER_ERROR");
        worker.poll();
        assertThat(job(attemptId).get("abandoned_claim_count")).isEqualTo(0);
    }

    private record Outage(int claims, long virtualMillis) { }

    /** PayPal이 503을 주는 동안 가상 시각이 {@code duration}에 닿거나 job이 PENDING을 벗어날 때까지 claim한다. */
    private Outage runOutage(UUID attemptId, Duration duration) {
        long virtualMillis = 0;
        int claims = 0;
        while ("PENDING".equals(jobStatus(attemptId)) && virtualMillis < duration.toMillis() && claims < 1_000) {
            int callsBefore = callCount(CREATE_ORDER_CALL);
            queueRejection("create-order", 503, "INTERNAL_SERVER_ERROR");
            worker.poll();
            // poll 첫 단계의 sweep이 창 만료로 job을 넘기면 claim 없이 끝난다(provider 호출 0).
            int calls = callCount(CREATE_ORDER_CALL) - callsBefore;
            assertThat(calls).as("at most one provider call per poll").isBetween(0, 1);
            claims += calls;
            if ("PENDING".equals(jobStatus(attemptId))) virtualMillis += advanceToNextAttempt(attemptId);
        }
        return new Outage(claims, virtualMillis);
    }

    /** create-order가 503이라 결과 불명(UNKNOWN)으로 남은 checkout 결제를 만든다. 복구 job은 바로 due다. */
    private UUID unknownCheckoutPayment() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Retry budget", null));
        String skuId = "retry-budget-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 17_200, 1));
        String buyerRef = "retry-budget-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse reserved = cartCheckouts.checkout(buyerRef, "retry-budget-" + UUID.randomUUID());
        queueRejection("create-order", 503, "INTERNAL_SERVER_ERROR");
        checkouts.submit(reserved.checkoutId());
        UUID attemptId = payments.findCheckout(reserved.checkoutId()).attemptId();
        assertThat(payments.findCheckout(reserved.checkoutId()).state()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(jobStatus(attemptId)).isEqualTo("PENDING");
        return attemptId;
    }

    /**
     * worker가 기록한 재예약 지연만큼 이 job의 시각 열을 과거로 옮긴다. 창 판정(retry_window_started_at)도
     * 같은 만큼 과거로 가므로 DB 시각으로 판정하는 운영 SQL이 가상 시간을 그대로 본다.
     */
    private long advanceToNextAttempt(UUID attemptId) {
        Long delay = jdbc.queryForObject("""
                SELECT CAST(GREATEST(0, EXTRACT(EPOCH FROM (available_at - CURRENT_TIMESTAMP)) * 1000) AS bigint)
                FROM payment_recovery_job WHERE payment_attempt_id = ?
                """, Long.class, attemptId);
        long millis = delay == null ? 0 : delay;
        jdbc.update("""
                UPDATE payment_recovery_job
                SET available_at = available_at - (? * INTERVAL '1 millisecond'),
                    retry_window_started_at = retry_window_started_at - (? * INTERVAL '1 millisecond'),
                    first_enqueued_at = first_enqueued_at - (? * INTERVAL '1 millisecond')
                WHERE payment_attempt_id = ?
                """, millis, millis, millis, attemptId);
        return millis;
    }

    private String jobStatus(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_recovery_job WHERE payment_attempt_id = ?", String.class, attemptId);
    }

    private Map<String, Object> job(UUID attemptId) {
        return jdbc.queryForMap("SELECT * FROM payment_recovery_job WHERE payment_attempt_id = ?", attemptId);
    }

    private int callCount(String key) {
        return providerCalls().getOrDefault(key, 0);
    }
}
