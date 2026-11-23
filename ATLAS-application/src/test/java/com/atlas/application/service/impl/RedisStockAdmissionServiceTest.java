package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.order.service.LocationClient;
import com.atlas.order.dto.request.StockClaimRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisStockAdmissionServiceTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static RedisStockAdmissionService gate(String uri, LocationClient inventory) {
        return new RedisStockAdmissionService(inventory, new SimpleMeterRegistry(), uri,
                Duration.ofMillis(200), Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofSeconds(2));
    }

    @Test
    void concurrentAdmissionNeverExceedsTheSeededQuantityAndReplayDoesNotDoubleCharge() throws Exception {
        LocationClient inventory = mock(LocationClient.class);
        when(inventory.availableStock(any(), any(), anyString())).thenReturn(100L);
        RedisStockAdmissionService gate = gate("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort(), inventory);
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
        RedisStockAdmissionService gate = gate("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort(), inventory);
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
        RedisStockAdmissionService gate = gate("redis://127.0.0.1:1", inventory);

        // 판단할 수 없으면 거절하지 않는다. 정본 검증(location의 조건부 UPDATE)이 뒤에 남아 있다.
        List<StockClaimRequest> claim = List.of(new StockClaimRequest(LOCATION, SELLER, "sku", 1));
        assertThat(gate.admit("order-x", claim)).isTrue();
        // 한 번 실패하면 cool-down 동안은 Redis를 건드리지 않으므로 요청마다 timeout을 기다리지 않는다.
        long started = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertThat(gate.admit("order-" + i, claim)).isTrue();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        gate.destroy();
    }
}
