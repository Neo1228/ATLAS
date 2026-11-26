package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.exception.LocationClientException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class HttpLocationClientTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void unknownReservationStatusIsRetryableUnknownInsteadOfAnAnswer() {
        // inventory-location 이 따로 배포되어 이 서비스가 모르는 상태를 먼저 보내도 확정 거절로 보지 않는다.
        server.enqueue(new MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"reservationId\":\"" + UUID.randomUUID() + "\",\"status\":\"ON_HOLD\","
                        + "\"expiresAt\":\"" + Instant.now().plusSeconds(120) + "\"}"));
        HttpLocationClient client = new HttpLocationClient(RestClient.builder(),
                LOCATION + "=" + server.url("/").toString(), "x".repeat(32),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 0, new SimpleMeterRegistry());

        assertThatThrownBy(() -> client.reserve(LOCATION, UUID.randomUUID(), "order-1:" + LOCATION,
                List.of(new LocationReservationItemRequest(UUID.randomUUID(), "sku-a", 1))))
                .isInstanceOfSatisfying(LocationClientException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(LocationClientException.Failure.RETRYABLE_UNKNOWN);
                    assertThat(exception.providerCode()).isEqualTo("UNKNOWN_RESERVATION_STATUS");
                });
    }

    @Test
    void requestThreadOverTheLimitIsRejectedBeforeSendingAndWorkerThreadsAreNotLimited() throws Exception {
        // 상한 1: 첫 요청 스레드 호출이 응답을 기다리는 동안 두 번째 요청 스레드 호출은 보내기 전에 거절되고,
        // 요청 밖 스레드(복구 worker)는 같은 시점에도 위치 서비스에 도달한다.
        server.enqueue(new MockResponse().setBodyDelay(1500, TimeUnit.MILLISECONDS).setBody("{\"available\":5}")
                .setHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setBody("{\"available\":7}").setHeader("Content-Type", "application/json"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        HttpLocationClient client = new HttpLocationClient(RestClient.builder(),
                LOCATION + "=" + server.url("/").toString(), "x".repeat(32),
                Duration.ofSeconds(1), Duration.ofSeconds(5), 1, meters);
        UUID seller = UUID.randomUUID();
        ExecutorService threads = Executors.newCachedThreadPool();
        try {
            CountDownLatch held = new CountDownLatch(1);
            Future<Long> holding = threads.submit(() -> {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
                try {
                    held.countDown();
                    return client.availableStock(LOCATION, seller, "sku-a");
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            held.await();
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            Future<Throwable> rejected = threads.submit(() -> {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
                try {
                    client.availableStock(LOCATION, seller, "sku-a");
                    return null;
                } catch (LocationClientException exception) {
                    return exception;
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
            assertThat(rejected.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(LocationClientException.class, exception -> {
                assertThat(exception.failure()).isEqualTo(LocationClientException.Failure.RETRYABLE_UNKNOWN);
                assertThat(exception.providerCode()).isEqualTo("INVENTORY_BUSY");
            });
            assertThat(server.getRequestCount()).as("rejected call sends nothing").isEqualTo(1);
            assertThat(threads.submit(() -> client.availableStock(LOCATION, seller, "sku-a")).get(5, TimeUnit.SECONDS))
                    .as("worker thread is not limited").isEqualTo(7L);
            assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(5L);
            assertThat(meters.get("atlas.inventory.location.bulkhead.rejected").counter().count()).isEqualTo(1.0);
        } finally {
            threads.shutdownNow();
        }
    }
}
