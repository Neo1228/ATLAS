package com.atlas.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.inventory.dto.request.InventoryRequest;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.service.InventoryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 秒杀(flash sale) 상황에서 재고 차감 경로가 어떻게 무너지는지 실서비스 경로로 측정한다.
 *
 * <p>왜 직접 SQL 이 아니라 실제 경로인가: 손으로 쓴 SQL 은 프로덕션 transaction 경계·advisory
 * lock·idempotency 를 재현하지 못한다. 여기서는 {@link InventoryService#reserve} 를 그대로
 * 호출해 HTTP 계층만 제거한 실제 경로를 측정한다.
 *
 * <p>확인 항목:
 * <ul>
 *   <li>oversell 이 발생하지 않는가 (업무 불변식, 반드시 실패해야 함)</li>
 *   <li>동시성이 늘수록 처리량이 어떻게 변하는가 (붕괴 지점)</li>
 *   <li>실패가 즉시 거절인지 락 대기인지 (지연 분포)</li>
 * </ul>
 *
 * <p>측정값은 EXP-044 에 기록한다. 임계값은 강제하지 않는다.
 */
@Testcontainers
@SpringBootTest
class InventoryFlashSaleContentionIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    private static final int INITIAL_STOCK = 50;
    private static final int ATTEMPTS_PER_BUYER = 4;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
    }

    @Autowired private InventoryService inventory;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;

    /** 소량 재고에 동시 구매자가 몰린다. 정확성을 강제하고 처리량을 기록한다. */
    @Test
    void flashSaleNeverOversellsAndReportsThroughput() throws Exception {
        for (int buyers : new int[] {8, 32, 64}) {
            Outcome outcome = hammerStock(buyers);
            System.out.printf(
                    "[flash-sale] buyers=%d attempts=%d success=%d soldOut=%d otherFailures=%d "
                            + "elapsedMs=%d tps=%.1f p50ms=%d p95ms=%d maxMs=%d%n",
                    buyers, outcome.attempts(), outcome.success(), outcome.soldOut(),
                    outcome.otherFailures(), outcome.elapsedMillis(), outcome.throughput(),
                    outcome.percentile(50), outcome.percentile(95), outcome.maxMillis());

            assertThat(outcome.success())
                    .as("성공 예약 수는 초기 재고를 넘을 수 없다 (buyers=%d)", buyers)
                    .isLessThanOrEqualTo(INITIAL_STOCK);
            assertThat(outcome.pool().available())
                    .as("차감 후 available 은 음수가 될 수 없다 (buyers=%d)", buyers)
                    .isNotNegative();
            assertThat(outcome.pool().available() + outcome.pool().reserved())
                    .as("가용+예약 합은 초기 재고와 같아야 한다 (buyers=%d)", buyers)
                    .isEqualTo(INITIAL_STOCK);
            assertThat(outcome.success())
                    .as("성공 수는 min(시도 수, 재고) 와 같아야 한다 (buyers=%d)", buyers)
                    .isEqualTo(Math.min(outcome.attempts(), INITIAL_STOCK));
        }
    }

    /**
     * 순수 락 경합 측정: 재고가 충분해 모든 요청이 성공하는 조건.
     *
     * <p>품절 fast-fail 이 처리량을 가리는 효과를 제거해, 단일 hot row 에 대한 조건부 UPDATE 가
     * 동시성에 따라 어떻게 직렬화되는지 본다. 이것이 秒杀 에서 실제로 병목이 되는 지점이다.
     */
    @Test
    void hotRowLockThroughputWithoutSellOut() throws Exception {
        for (int buyers : new int[] {8, 32, 64, 128}) {
            Outcome outcome = hammerStockWithStock(buyers, 1_000_000);
            System.out.printf(
                    "[hot-row] buyers=%d attempts=%d success=%d elapsedMs=%d tps=%.1f "
                            + "p50ms=%d p95ms=%d maxMs=%d%n",
                    buyers, outcome.attempts(), outcome.success(), outcome.elapsedMillis(),
                    outcome.throughput(), outcome.percentile(50), outcome.percentile(95),
                    outcome.maxMillis());
            assertThat(outcome.success()).as("재고가 충분하면 모두 성공").isEqualTo(outcome.attempts());
        }
    }

    private Outcome hammerStock(int buyers) throws Exception {
        return hammerStockWithStock(buyers, INITIAL_STOCK);
    }

    private Outcome hammerStockWithStock(int buyers, int initialStock) throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = "flash-" + UUID.randomUUID();
        UUID productId = products.createProduct(sellerId,
                new ProductRequest.CreateProduct("flash-sale-" + UUID.randomUUID(), "description")).productId();
        products.registerSku(sellerId, productId, new ProductRequest.RegisterSellerSku(sku, 1000, initialStock));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < buyers; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int repeat = 0; repeat < ATTEMPTS_PER_BUYER; repeat++) {
                    long began = System.nanoTime();
                    try {
                        inventory.reserve(new InventoryRequest.ReserveInventory(
                                UUID.randomUUID(), null, sellerId, sku, 1));
                        success.incrementAndGet();
                    } catch (InventoryException exception) {
                        if (exception.code() == InventoryException.Code.UNAVAILABLE
                                || exception.code() == InventoryException.Code.INSUFFICIENT_STOCK) {
                            soldOut.incrementAndGet();
                        } else {
                            otherFailures.incrementAndGet();
                        }
                    } finally {
                        latencies.add((System.nanoTime() - began) / 1_000_000);
                    }
                }
                return null;
            }));
        }

        long beganAt = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(180, TimeUnit.SECONDS);
        }
        long elapsedMillis = (System.nanoTime() - beganAt) / 1_000_000;
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        InventoryResponse.Pool pool = inventory.find(sellerId, sku);
        return new Outcome(latencies, buyers * ATTEMPTS_PER_BUYER, success.get(), soldOut.get(),
                otherFailures.get(), elapsedMillis, pool);
    }

    private record Outcome(List<Long> latencies, int attempts, int success, int soldOut,
            int otherFailures, long elapsedMillis, InventoryResponse.Pool pool) {
        double throughput() {
            return elapsedMillis == 0 ? 0 : attempts * 1000.0 / elapsedMillis;
        }

        long percentile(int percentile) {
            if (latencies.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
            return sorted.get(Math.max(0, index));
        }

        long maxMillis() {
            return latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }
}
