package com.atlas.application.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.exception.LocationClientException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
                Duration.ofSeconds(1), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.reserve(LOCATION, UUID.randomUUID(), "order-1:" + LOCATION,
                List.of(new LocationReservationItemRequest(UUID.randomUUID(), "sku-a", 1))))
                .isInstanceOfSatisfying(LocationClientException.class, exception -> {
                    assertThat(exception.failure()).isEqualTo(LocationClientException.Failure.RETRYABLE_UNKNOWN);
                    assertThat(exception.providerCode()).isEqualTo("UNKNOWN_RESERVATION_STATUS");
                });
    }
}
