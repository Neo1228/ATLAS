package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.application.config.RedisStockAdmissionControlConfiguration;
import com.atlas.order.service.LocationClient;
import com.atlas.order.dto.request.StockClaimRequest;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisStockAdmissionControlTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private final List<RedisClient> clients = new ArrayList<>();

    @AfterEach
    void shutdownClients() {
        clients.forEach(RedisClient::shutdown);
        clients.clear();
    }

    /**
     * 명령 timeout을 넉넉히 둔다. 운영 기본값(200ms)은 연결 초기화 핸드셰이크까지 덮는다. 호스트가 바쁘면 첫 호출이
     * timeout으로 fail-open(통과)되고, 테스트에서는 "거절돼야 하는 요청이 통과했다"는 단정 실패로 나타난다(EXP-099).
     */
    private RedisStockAdmissionControl gate(String uri, LocationClient inventory) {
        return gate(uri, inventory, new SimpleMeterRegistry());
    }

    private RedisStockAdmissionControl gate(String uri, LocationClient inventory, SimpleMeterRegistry meters) {
        RedisClient client = new RedisStockAdmissionControlConfiguration().admissionGateRedisClient(uri, Duration.ofSeconds(30));
        clients.add(client);
        return new RedisStockAdmissionControl(inventory, meters, client,
                Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(2));
    }

    @Test
    void concurrentAdmissionNeverExceedsTheSeededQuantityAndReplayDoesNotDoubleCharge() throws Exception {
        LocationClient inventory = mock(LocationClient.class);
        when(inventory.availableStock(any(), any(), anyString())).thenReturn(100L);
        RedisStockAdmissionControl gate = gate("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort(), inventory);
        String sku = "hot-" + UUID.randomUUID();
        List<StockClaimRequest> claim = List.of(new StockClaimRequest(LOCATION, SELLER, sku, 1));

        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                String key = "order-" + i;
                calls.add(pool.submit(() -> {
                    if (gate.admit(key, claim)) {
                        admitted.incrementAndGet();
                    }
                }));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 1,000건이 동시에 와도 시드한 100을 넘겨 통과시키지 않는다. 시드는 SKU당 한 번만 원격에서 읽는다.
        assertThat(admitted.get()).isEqualTo(100);
        verify(inventory, times(1)).availableStock(any(), any(), anyString());
        // 같은 key의 재시도는 처음 판정을 그대로 돌려주고 수량을 다시 차감하지 않는다.
        assertThat(gate.admit("order-0", claim)).isEqualTo(gate.admit("order-0", claim));
        assertThat(gate.admit("order-late", claim)).isFalse();
        gate.destroy();
    }

    @Test
    void releaseReturnsQuantityOnceAndConfirmedAdmissionsAreNeverReturned() {
        LocationClient inventory = mock(LocationClient.class);
        when(inventory.availableStock(any(), any(), anyString())).thenReturn(2L);
        RedisStockAdmissionControl gate = gate("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort(), inventory);
        List<StockClaimRequest> one = List.of(new StockClaimRequest(LOCATION, SELLER, "ret-" + UUID.randomUUID(), 1));

        assertThat(gate.admit("a", one)).isTrue();
        assertThat(gate.admit("b", one)).isTrue();
        assertThat(gate.admit("c", one)).isFalse();

        // 예약이 실패·취소된 a는 수량을 돌려주고, 같은 key를 다시 release해도 두 번 돌려주지 않는다.
        gate.release("a");
        gate.release("a");
        assertThat(gate.admit("d", one)).isTrue();
        assertThat(gate.admit("e", one)).isFalse();

        // 예약이 확정된 b는 release가 와도 돌려주지 않는다(이미 판매 재고로 잡혔다).
        gate.confirm("b");
        gate.release("b");
        assertThat(gate.admit("f", one)).isFalse();
        gate.destroy();
    }

    @Test
    void unreachableRedisFallsThroughInsteadOfRejectingOrders() {
        LocationClient inventory = mock(LocationClient.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisStockAdmissionControl gate = gate("redis://127.0.0.1:1", inventory, meters);

        // 판단할 수 없으면 거절하지 않는다. 정본 검증(location의 조건부 UPDATE)이 뒤에 남아 있다.
        List<StockClaimRequest> claim = List.of(new StockClaimRequest(LOCATION, SELLER, "sku", 1));
        assertThat(gate.admit("order-x", claim)).isTrue();
        // 한 번 실패하면 cool-down 동안은 Redis를 건드리지 않으므로 요청마다 timeout을 기다리지 않는다.
        long started = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertThat(gate.admit("order-" + i, claim)).isTrue();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        // 통과한 이유를 구분한다: 처음 한 번은 연결 오류, 이후 200번은 breaker가 열려 있어서다.
        assertThat(failOpenCount(meters, "error")).isEqualTo(1);
        assertThat(failOpenCount(meters, "breaker_open")).isEqualTo(200);
        assertThat(meters.get("atlas.order.admission.gate").tag("result", "fail_open").counter().count())
                .isEqualTo(201);
        gate.destroy();
    }

    @Test
    void manySkusSharingSeedLockStripesAreEachSeededOnceAndAdmitOnlyTheirStock() throws Exception {
        LocationClient inventory = mock(LocationClient.class);
        when(inventory.availableStock(any(), any(), anyString())).thenReturn(1L);
        RedisStockAdmissionControl gate = gate("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort(), inventory);
        // stripe 수(256)보다 많은 SKU로 서로 다른 SKU가 같은 lock을 공유하는 경우를 만든다.
        int skuCount = 600;
        String run = UUID.randomUUID().toString();
        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int sku = 0; sku < skuCount; sku++) {
                List<StockClaimRequest> claim = List.of(new StockClaimRequest(LOCATION, SELLER, "stripe-" + run + "-" + sku, 1));
                for (int buyer = 0; buyer < 4; buyer++) {
                    String key = "order-" + run + "-" + sku + "-" + buyer;
                    calls.add(pool.submit(() -> {
                        if (gate.admit(key, claim)) {
                            admitted.incrementAndGet();
                        }
                    }));
                }
            }
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).isEqualTo(skuCount);
        verify(inventory, times(skuCount)).availableStock(any(), any(), anyString());
        gate.destroy();
    }

    private static double failOpenCount(SimpleMeterRegistry meters, String reason) {
        return meters.get("atlas.order.admission.gate.fail_open").tag("reason", reason).counter().count();
    }
}
