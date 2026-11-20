package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.ReserveInventoryRequest;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.payment.service.PaymentService;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 동시 결제 준비가 중복 attempt/중복 provider 호출을 만들지 않는지 실경로로 검증한다.
 *
 * <p>왜 필요한가: 인메모리 락({@code OwnerLockRegistry})은 단일 JVM 에서만 유효하고, replica 간에는
 * DB 가 최종 방어선이다. 이 테스트는 그 방어선이 실제로 중복 attempt 를 막는지 확인한다.
 *
 * <p>이 테스트가 증명하는 것: 같은 주문에 동시 준비가 몰려도 attempt 는 1개다.
 * 증명하지 못하는 것: 다중 replica 에서의 provider 호출 배수(별도 측정 필요).
 */
@Testcontainers
@SpringBootTest
class ConcurrentPaymentPreparationIntegrationTest extends PayPalIntegrationTestSupport {
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
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
    }

    @Autowired private InventoryService inventory;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private PaymentService payments;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 같은 주문에 동시 준비 요청을 보낸다.
     *
     * <p>기대: attempt 1개, 모든 호출이 같은 attemptId 를 본다.
     */
    @Test
    void concurrentPreparationCreatesExactlyOneAttempt() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = "concurrent-prep-" + UUID.randomUUID();
        UUID productId = products.createProduct(sellerId,
                new CreateProductRequest("concurrent-prep-" + UUID.randomUUID(), "d")).productId();
        products.registerSku(sellerId, productId, new RegisterSellerSkuRequest(sku, 5000, 10));

        UUID orderId = UUID.randomUUID();
        inventory.reserve(new ReserveInventoryRequest(orderId, null, sellerId, sku, 1));

        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < callers; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return payments.prepareInitial(orderId, BigDecimal.valueOf(5000)).attemptId();
                } catch (RuntimeException exception) {
                    failures.incrementAndGet();
                    return null;
                }
            }));
        }
        start.countDown();
        List<UUID> attemptIds = new ArrayList<>();
        for (Future<UUID> future : futures) {
            UUID id = future.get(60, TimeUnit.SECONDS);
            if (id != null) attemptIds.add(id);
        }
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        Integer attempts = jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempt WHERE order_id = ?", Integer.class, orderId);

        System.out.printf("[concurrent-prep] callers=%d failures=%d distinctAttemptIds=%d rows=%d%n",
                callers, failures.get(), attemptIds.stream().distinct().count(), attempts);

        assertThat(attempts).as("같은 주문의 동시 준비는 attempt 를 1개만 만든다").isEqualTo(1);
        assertThat(attemptIds.stream().distinct())
                .as("모든 호출은 같은 attempt 를 본다").hasSize(1);
    }
}
