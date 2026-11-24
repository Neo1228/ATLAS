package com.atlas.order;

import com.atlas.order.dto.request.LocationReservationRequest.Item;
import com.atlas.order.dto.request.LocationReservationRequest.LocationAllocation;
import com.atlas.order.dto.request.LocationReservationRequest;
import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.LocationReservationState;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.repository.LocationReservationRepository;
import com.atlas.order.service.LocationClient;
import com.atlas.order.service.LocationReservationService;
import com.atlas.order.service.StockAdmissionControl;
import com.atlas.order.service.impl.LocationReservationServiceImpl;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class LocationReservationServiceIntegrationTest extends PayPalIntegrationTestSupport {
    private static final UUID LOCATION_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final String SERVICE_TOKEN = "integration-coordinator-service-token-32bytes";
    private static final MockWebServer INVENTORY_A = startServer();
    private static final MockWebServer INVENTORY_B = startServer();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(properties);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.payment.recovery.worker.enabled", () -> "false");
        properties.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
        properties.add("atlas.order-inventory-reservation.enabled", () -> "true");
        properties.add("atlas.order-inventory-reservation.recovery.worker-enabled", () -> "false");
        properties.add("atlas.order-inventory-reservation.api-token", () -> SERVICE_TOKEN);
        properties.add("atlas.order-inventory-reservation.service-token", () -> SERVICE_TOKEN);
        properties.add("atlas.order-inventory-reservation.location-endpoints", () -> endpointConfiguration());
        properties.add("atlas.order-inventory-reservation.read-timeout", () -> "PT6S");
        properties.add("atlas.order-inventory-reservation.recovery.base-backoff", () -> "PT1S");
    }

    @Autowired
    private LocationReservationServiceImpl coordinator;
    @Autowired
    private LocationReservationRepository repository;
    @Autowired
    private LocationClient inventoryClient;
    @Autowired
    private Clock clock;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @AfterAll
    static void shutdownInventoryStubs() throws IOException {
        INVENTORY_A.shutdown();
        INVENTORY_B.shutdown();
    }

    @BeforeEach
    void clearWorkflowState() throws InterruptedException {
        jdbc.update("DELETE FROM order_inventory_location_item");
        jdbc.update("DELETE FROM order_inventory_location_command");
        jdbc.update("DELETE FROM order_inventory_reservation_workflow");
        drain(INVENTORY_A);
        drain(INVENTORY_B);
    }

    @Test
    void secondLocationStockFailureCompensatesEarlierReservation() throws Exception {
        UUID remoteReservationId = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(remoteReservationId));
        INVENTORY_B.enqueue(new MockResponse().setResponseCode(409)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"no stock\"}"));
        INVENTORY_A.enqueue(cancelled(remoteReservationId));
        UUID orderId = UUID.randomUUID();
        String key = "partial-location-" + UUID.randomUUID();

        ReservationWorkflowResponse.LocationReservation response = coordinator.submit(key,
                request(orderId, allocation(LOCATION_B, "sku-b"), allocation(LOCATION_A, "sku-a")));

        assertThat(response.status()).isEqualTo(LocationReservationWorkflowState.FAILED);
        assertThat(response.locations().stream()
                .map(ReservationWorkflowResponse.LocationReservation.LocationResult::status).toList())
                .containsExactly(LocationReservationState.RELEASED, LocationReservationState.FAILED);
        RecordedRequest firstA = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest secondA = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest firstB = INVENTORY_B.takeRequest(5, TimeUnit.SECONDS);
        assertThat(firstA.getPath()).endsWith("/reservations");
        assertThat(secondA.getPath()).endsWith("/reservations/cancel");
        assertThat(firstA.getHeader("Idempotency-Key")).isEqualTo(secondA.getHeader("Idempotency-Key"));
        assertThat(firstB.getMethod()).isEqualTo("POST");
    }

    @Test
    void cancelReleasesEveryReservedLocationAndRepeatedCancelIsNoOp() throws Exception {
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA));
        INVENTORY_B.enqueue(reserved(reservationB));
        ReservationWorkflowResponse.LocationReservation reserved = coordinator.submit("cancel-" + UUID.randomUUID(),
                request(UUID.randomUUID(), allocation(LOCATION_A, "sku-a"), allocation(LOCATION_B, "sku-b")));
        assertThat(reserved.status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
        INVENTORY_A.enqueue(cancelled(reservationA));
        INVENTORY_B.enqueue(cancelled(reservationB));

        ReservationWorkflowResponse.LocationReservation cancelled = coordinator.cancel(reserved.workflowId());
        ReservationWorkflowResponse.LocationReservation repeated = coordinator.cancel(reserved.workflowId());

        assertThat(cancelled.status()).isEqualTo(LocationReservationWorkflowState.CANCELLED);
        assertThat(cancelled.locations().stream()
                .map(ReservationWorkflowResponse.LocationReservation.LocationResult::status).toList())
                .containsOnly(LocationReservationState.RELEASED);
        assertThat(repeated.status()).isEqualTo(LocationReservationWorkflowState.CANCELLED);
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        assertThat(INVENTORY_A.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(INVENTORY_B.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unknownCancelOutcomeStaysCancelPendingUntilRecoveryReleasesIt() throws Exception {
        UUID reservationA = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA));
        ReservationWorkflowResponse.LocationReservation reserved = coordinator.submit("cancel-retry-" + UUID.randomUUID(),
                request(UUID.randomUUID(), allocation(LOCATION_A, "sku-a")));
        INVENTORY_A.enqueue(new MockResponse().setResponseCode(503));

        ReservationWorkflowResponse.LocationReservation pending = coordinator.cancel(reserved.workflowId());

        assertThat(pending.status()).isEqualTo(LocationReservationWorkflowState.CANCEL_PENDING);
        assertThat(pending.locations().getFirst().status()).isEqualTo(LocationReservationState.RELEASE_PENDING);
        INVENTORY_A.enqueue(cancelled(reservationA));
        jdbc.update("UPDATE order_inventory_reservation_workflow SET next_attempt_at = now() - interval '1 second'"
                + " WHERE workflow_id = ?", reserved.workflowId());

        assertThat(coordinator.recoverDue(10)).isEqualTo(1);

        ReservationWorkflowResponse.LocationReservation recovered = coordinator.find(reserved.workflowId());
        assertThat(recovered.status()).isEqualTo(LocationReservationWorkflowState.CANCELLED);
        assertThat(recovered.locations().getFirst().status()).isEqualTo(LocationReservationState.RELEASED);
        INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest firstCancel = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest secondCancel = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        assertThat(secondCancel.getHeader("Idempotency-Key")).isEqualTo(firstCancel.getHeader("Idempotency-Key"));
    }

    @Test
    void cancelAfterLostClaimResponseRestoresRemoteClaimedStockInsteadOfNeedingReview() throws Exception {
        // 원격 claim은 commit됐지만 응답이 유실되어 로컬 위치 상태는 RESERVED로 남은 장면이다.
        UUID reservationA = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA));
        ReservationWorkflowResponse.LocationReservation reserved = coordinator.submit("lost-claim-" + UUID.randomUUID(),
                request(UUID.randomUUID(), allocation(LOCATION_A, "sku-a")));
        INVENTORY_A.enqueue(new MockResponse().setResponseCode(503));
        assertThat(coordinator.claim(reserved.workflowId()).name()).isEqualTo("PENDING");
        assertThat(coordinator.find(reserved.workflowId()).locations().getFirst().status()).isEqualTo(LocationReservationState.RESERVED);

        // 원격은 이미 CLAIMED라 cancel이 INVALID_STATE로 거부된다. 판매가 확정되지 않았으므로 restore로 되돌려야 한다.
        INVENTORY_A.enqueue(new MockResponse().setResponseCode(409)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"code\":\"INVALID_STATE\",\"message\":\"Claimed stock cannot be cancelled\"}"));
        INVENTORY_A.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationA + "\",\"status\":\"RELEASED\","
                        + "\"expiresAt\":\"" + Instant.now().plusSeconds(120) + "\"}"));

        ReservationWorkflowResponse.LocationReservation cancelled = coordinator.cancel(reserved.workflowId());

        assertThat(cancelled.status()).isEqualTo(LocationReservationWorkflowState.CANCELLED);
        assertThat(cancelled.locations().getFirst().status()).isEqualTo(LocationReservationState.RELEASED);
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/restore");
        assertThat(INVENTORY_A.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void gateRejectionLeavesNoWorkflowRowAndMakesNoRemoteCall() throws Exception {
        // 재고 admission을 거절하도록 생성자로 주입한 별도 coordinator를 쓴다(공유 빈을 바꾸지 않는다).
        LocationReservationService gated = new LocationReservationServiceImpl(repository, inventoryClient, clock,
                transactionManager, Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofSeconds(1),
                Duration.ofSeconds(10), 8, Optional.of((StockAdmissionControl) (key, claims) -> false));
        String key = "gated-" + UUID.randomUUID();
        assertThatThrownBy(() -> gated.submit(key,
                request(UUID.randomUUID(), allocation(LOCATION_A, "sku-a"))))
                .isInstanceOf(OrderException.class);

        // 확실히 거절될 요청은 durable 쓰기도 원격 호출도 만들지 않는다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_inventory_reservation_workflow WHERE idempotency_key = ?",
                Integer.class, key)).isZero();
        assertThat(INVENTORY_A.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void operatorReleaseTurnsNeedsReviewBackIntoCompensationExactlyOnce() throws Exception {
        UUID reservationA = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA));
        ReservationWorkflowResponse.LocationReservation reserved = coordinator.submit("operator-" + UUID.randomUUID(),
                request(UUID.randomUUID(), allocation(LOCATION_A, "sku-a")));
        // 보상 재시도를 모두 쓴 뒤 사람이 봐야 하는 상태를 재현한다.
        jdbc.update("UPDATE order_inventory_reservation_workflow SET workflow_state = 'NEEDS_REVIEW', "
                + "attempt_count = 8 WHERE workflow_id = ?", reserved.workflowId());
        assertThat(coordinator.needsReview(10)).extracting(ReservationWorkflowResponse.LocationReservation::workflowId)
                .contains(reserved.workflowId());
        INVENTORY_A.enqueue(cancelled(reservationA));

        ReservationWorkflowResponse.LocationReservation released = coordinator.releaseNeedsReview(reserved.workflowId());
        ReservationWorkflowResponse.LocationReservation repeated = coordinator.releaseNeedsReview(reserved.workflowId());

        assertThat(released.locations().getFirst().status()).isEqualTo(LocationReservationState.RELEASED);
        assertThat(released.status()).isIn(LocationReservationWorkflowState.FAILED, LocationReservationWorkflowState.CANCELLED);
        assertThat(repeated.status()).isEqualTo(released.status());
        assertThat(coordinator.needsReview(10)).extracting(ReservationWorkflowResponse.LocationReservation::workflowId)
                .doesNotContain(reserved.workflowId());
        // 두 번째 호출은 원격 호출을 늘리지 않는다(reserve 1회 + cancel 1회).
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        assertThat(INVENTORY_A.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void holdTooCloseToExpiryAfterTheNextLocationRespondsIsCompensated() throws Exception {
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA, Instant.now().plusSeconds(12)));
        INVENTORY_B.enqueue(reserved(reservationB).setBodyDelay(4, TimeUnit.SECONDS));
        INVENTORY_A.enqueue(cancelled(reservationA));
        INVENTORY_B.enqueue(cancelled(reservationB));
        UUID orderId = UUID.randomUUID();
        String key = "expiry-during-location-latency-" + UUID.randomUUID();

        ReservationWorkflowResponse.LocationReservation response = coordinator.submit(key,
                request(orderId, allocation(LOCATION_A, "sku-a"), allocation(LOCATION_B, "sku-b")));

        assertThat(response.status()).isEqualTo(LocationReservationWorkflowState.FAILED);
        assertThat(repository.findByWorkflowId(response.workflowId()).lastErrorCode())
                .isEqualTo("RESERVATION_EXPIRED");
        assertThat(response.locations().stream()
                .map(ReservationWorkflowResponse.LocationReservation.LocationResult::status).toList())
                .containsExactly(LocationReservationState.RELEASED, LocationReservationState.RELEASED);
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations");
        assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
    }

    @Test
    void lostRemoteResponseIsRecoveredFromDurableIntentAfterCoordinatorRecreation() throws Exception {
        INVENTORY_A.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        UUID orderId = UUID.randomUUID();
        String key = "lost-coordinator-response-" + UUID.randomUUID();
        ReservationWorkflowResponse.LocationReservation pending = coordinator.submit(key,
                request(orderId, allocation(LOCATION_A, "sku-a")));

        assertThat(pending.status()).isEqualTo(LocationReservationWorkflowState.RETRY_PENDING);
        RecordedRequest originalRequest = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
        String remoteIdempotencyKey = originalRequest.getHeader("Idempotency-Key");
        UUID remoteReservationId = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(remoteReservationId));

        LocationReservationService restartedCoordinator =
                new LocationReservationServiceImpl(repository, inventoryClient, clock, transactionManager,
                        Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 8, Optional.empty());
        ReservationWorkflowResponse.LocationReservation recovered = restartedCoordinator.retry(pending.workflowId());
        RecordedRequest retriedRequest = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);

        assertThat(recovered.status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
        assertThat(recovered.workflowId()).isEqualTo(pending.workflowId());
        assertThat(retriedRequest.getHeader("Idempotency-Key")).isEqualTo(remoteIdempotencyKey);
        assertThat(recovered.locations().getFirst().reservationId()).isEqualTo(remoteReservationId);
        assertThat(repository.findByWorkflowId(pending.workflowId()).state()).isEqualTo(LocationReservationWorkflowState.RESERVED);
    }

    @Test
    void retryDoesNotStealAnActiveWorkflowLease() throws Exception {
        String idempotencyKey = "active-workflow-lease-" + UUID.randomUUID();
        ReservationWorkflowResponse.LocationReservation staged = coordinator.stage(idempotencyKey,
                request(UUID.randomUUID(), allocation(LOCATION_A, "lease-sku")));
        Instant now = clock.instant();
        UUID leaseToken = repository.claimWorkflow(
                staged.workflowId(), now, now.plusSeconds(30));
        assertThat(leaseToken).isNotNull();

        ReservationWorkflowResponse.LocationReservation retried = coordinator.retry(staged.workflowId());

        assertThat(retried.workflowId()).isEqualTo(staged.workflowId());
        UUID persistedLeaseToken = jdbc.queryForObject("""
                SELECT lease_token
                FROM order_inventory_reservation_workflow
                WHERE workflow_id = ?
                """, UUID.class, staged.workflowId());
        assertThat(persistedLeaseToken).isEqualTo(leaseToken);
        assertThat(INVENTORY_A.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void recoveryClaimsEachWorkflowImmediatelyBeforeProcessing() throws Exception {
        String firstKey = "just-in-time-first-" + UUID.randomUUID();
        String secondKey = "just-in-time-second-" + UUID.randomUUID();
        ReservationWorkflowResponse.LocationReservation first = coordinator.stage(firstKey,
                request(UUID.randomUUID(), allocation(LOCATION_A, "jit-first")));
        ReservationWorkflowResponse.LocationReservation second = coordinator.stage(secondKey,
                request(UUID.randomUUID(), allocation(LOCATION_A, "jit-second")));
        List<UUID> dueOrder = jdbc.queryForList("""
                SELECT workflow_id
                FROM order_inventory_reservation_workflow
                WHERE workflow_id IN (?, ?)
                ORDER BY next_attempt_at, workflow_id
                """, UUID.class, first.workflowId(), second.workflowId());
        assertThat(dueOrder).hasSize(2);
        UUID firstWorkflowId = dueOrder.getFirst();
        UUID queuedWorkflowId = dueOrder.get(1);
        String firstLocationKey = jdbc.queryForObject("""
                SELECT idempotency_key FROM order_inventory_location_command
                WHERE workflow_id = ? AND fulfillment_location_id = ?
                """, String.class, firstWorkflowId, LOCATION_A);
        String queuedLocationKey = jdbc.queryForObject("""
                SELECT idempotency_key FROM order_inventory_location_command
                WHERE workflow_id = ? AND fulfillment_location_id = ?
                """, String.class, queuedWorkflowId, LOCATION_A);
        INVENTORY_A.enqueue(reserved(UUID.randomUUID()).setHeadersDelay(1, TimeUnit.SECONDS));
        INVENTORY_A.enqueue(reserved(UUID.randomUUID()).setHeadersDelay(3, TimeUnit.SECONDS));
        int requestsBefore = INVENTORY_A.getRequestCount();

        try (ExecutorService pollers = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstPoll = pollers.submit(() -> coordinator.recoverDue(2));
            RecordedRequest firstRequest = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
            assertThat(firstRequest.getHeader("Idempotency-Key")).isEqualTo(firstLocationKey);
            UUID queuedLease = jdbc.queryForObject("""
                    SELECT lease_token FROM order_inventory_reservation_workflow WHERE workflow_id = ?
                    """, UUID.class, queuedWorkflowId);
            Integer queuedAttempts = jdbc.queryForObject("""
                    SELECT attempt_count FROM order_inventory_reservation_workflow WHERE workflow_id = ?
                    """, Integer.class, queuedWorkflowId);
            assertThat(queuedLease).isNull();
            assertThat(queuedAttempts).isZero();

            Future<Integer> secondPoll = pollers.submit(() -> coordinator.recoverDue(1));
            RecordedRequest secondRequest = INVENTORY_A.takeRequest(5, TimeUnit.SECONDS);
            assertThat(secondRequest.getHeader("Idempotency-Key")).isEqualTo(queuedLocationKey);
            RecordedRequest staleRequest = INVENTORY_A.takeRequest(2, TimeUnit.SECONDS);
            if (staleRequest != null) INVENTORY_A.enqueue(reserved(UUID.randomUUID()));
            assertThat(staleRequest).isNull();

            assertThat(firstPoll.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(secondPoll.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(INVENTORY_A.getRequestCount() - requestsBefore).isEqualTo(2);
        assertThat(coordinator.find(firstWorkflowId).status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
        assertThat(coordinator.find(queuedWorkflowId).status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
    }

    @Test
    void renewsLeaseBeforeNextRemoteCallWhenFanoutConsumesHalfTheLease() throws Exception {
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = "lease-renewal-" + UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(UUID.randomUUID()).setHeadersDelay(1700, TimeUnit.MILLISECONDS));
        INVENTORY_B.enqueue(reserved(UUID.randomUUID()).setHeadersDelay(1200, TimeUnit.MILLISECONDS));
        LocationReservationService shortLeaseCoordinator =
                new LocationReservationServiceImpl(repository, inventoryClient, clock, transactionManager,
                        Duration.ofMillis(2500), Duration.ofMillis(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 8, Optional.empty());

        try (ExecutorService submitter = Executors.newSingleThreadExecutor()) {
            Future<ReservationWorkflowResponse.LocationReservation> submission = submitter.submit(() ->
                    shortLeaseCoordinator.submit(idempotencyKey,
                            request(orderId, allocation(LOCATION_A, "lease-renew-a"),
                                    allocation(LOCATION_B, "lease-renew-b"))));
            assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            UUID workflowId = jdbc.queryForObject("""
                    SELECT workflow_id FROM order_inventory_reservation_workflow WHERE idempotency_key = ?
                    """, UUID.class, idempotencyKey);
            Boolean leaseHasRenewalMargin = jdbc.queryForObject("""
                    SELECT lease_until > CURRENT_TIMESTAMP + INTERVAL '1 second'
                    FROM order_inventory_reservation_workflow WHERE workflow_id = ?
                    """, Boolean.class, workflowId);
            assertThat(leaseHasRenewalMargin).isTrue();
            assertThat(submission.get(5, TimeUnit.SECONDS).status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
        }
    }

    @Test
    void retryScheduledDuringPollWaitsForTheNextRecoveryPoll() throws Exception {
        String firstKey = "poll-backoff-first-" + UUID.randomUUID();
        String secondKey = "poll-backoff-second-" + UUID.randomUUID();
        ReservationWorkflowResponse.LocationReservation first = coordinator.stage(firstKey,
                request(UUID.randomUUID(), allocation(LOCATION_A, "poll-backoff-first")));
        ReservationWorkflowResponse.LocationReservation second = coordinator.stage(secondKey,
                request(UUID.randomUUID(), allocation(LOCATION_A, "poll-backoff-second")));
        List<UUID> dueOrder = jdbc.queryForList("""
                SELECT workflow_id
                FROM order_inventory_reservation_workflow
                WHERE workflow_id IN (?, ?)
                ORDER BY next_attempt_at, workflow_id
                """, UUID.class, first.workflowId(), second.workflowId());
        UUID firstWorkflowId = dueOrder.getFirst();
        UUID secondWorkflowId = dueOrder.get(1);
        INVENTORY_A.enqueue(new MockResponse().setResponseCode(503));
        INVENTORY_A.enqueue(reserved(UUID.randomUUID()).setHeadersDelay(1500, TimeUnit.MILLISECONDS));
        int requestsBefore = INVENTORY_A.getRequestCount();

        try (ExecutorService poller = Executors.newSingleThreadExecutor()) {
            Future<Integer> recovery = poller.submit(() -> coordinator.recoverDue(3));
            assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            RecordedRequest samePollRetry = INVENTORY_A.takeRequest(3, TimeUnit.SECONDS);
            if (samePollRetry != null) INVENTORY_A.enqueue(reserved(UUID.randomUUID()));

            assertThat(samePollRetry).isNull();
            assertThat(recovery.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        }

        assertThat(INVENTORY_A.getRequestCount() - requestsBefore).isEqualTo(2);
        assertThat(coordinator.find(firstWorkflowId).status()).isEqualTo(LocationReservationWorkflowState.RETRY_PENDING);
        assertThat(coordinator.find(secondWorkflowId).status()).isEqualTo(LocationReservationWorkflowState.RESERVED);
    }

    @Test
    void expiredLeaseCannotBeRenewedByItsOldToken() {
        ReservationWorkflowResponse.LocationReservation staged = coordinator.stage("expired-lease-" + UUID.randomUUID(),
                request(UUID.randomUUID(), allocation(LOCATION_A, "expired-lease-sku")));
        Instant now = clock.instant();
        UUID firstLease = repository.claimWorkflow(staged.workflowId(), now, now.plusSeconds(1));
        assertThat(firstLease).isNotNull();

        assertThat(repository.renewLease(staged.workflowId(), firstLease,
                now.plusSeconds(2), now.plusSeconds(32))).isFalse();
        UUID reclaimedLease = repository.claimWorkflow(
                staged.workflowId(), now.plusSeconds(2), now.plusSeconds(32));
        assertThat(reclaimedLease).isNotNull().isNotEqualTo(firstLease);
        assertThat(repository.renewLease(staged.workflowId(), firstLease,
                now.plusSeconds(3), now.plusSeconds(33))).isFalse();
    }

    @Test
    void cancellationFencingRollsBackAllBufferedRemoteReservationResults() throws Exception {
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        INVENTORY_A.enqueue(reserved(reservationA));
        INVENTORY_B.enqueue(reserved(reservationB).setHeadersDelay(1, TimeUnit.SECONDS));
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = "batch-lease-loss-" + UUID.randomUUID();

        try (ExecutorService submitter = Executors.newSingleThreadExecutor()) {
            Future<ReservationWorkflowResponse.LocationReservation> submission = submitter.submit(() ->
                    coordinator.submit(idempotencyKey,
                            request(orderId, allocation(LOCATION_A, "batch-a"), allocation(LOCATION_B, "batch-b"))));
            assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS)).isNotNull();

            UUID workflowId = jdbc.queryForObject("""
                    SELECT workflow_id
                    FROM order_inventory_reservation_workflow
                    WHERE idempotency_key = ?
                    """, UUID.class, idempotencyKey);
            assertThat(repository.requestCancellation(workflowId, clock.instant())).isTrue();
            ReservationWorkflowResponse.LocationReservation cancelled = submission.get(5, TimeUnit.SECONDS);

            assertThat(cancelled.status()).isEqualTo(LocationReservationWorkflowState.CANCEL_PENDING);
            List<String> uncommittedStates = jdbc.queryForList("""
                    SELECT command_state
                    FROM order_inventory_location_command
                    WHERE workflow_id = ?
                    ORDER BY fulfillment_location_id
                    """, String.class, workflowId);
            assertThat(uncommittedStates).containsExactly("PENDING", "PENDING");

            INVENTORY_A.enqueue(cancelled(reservationA));
            INVENTORY_B.enqueue(cancelled(reservationB));
            assertThat(coordinator.recoverDue(1)).isEqualTo(1);

            assertThat(repository.findByWorkflowId(workflowId).state()).isEqualTo(LocationReservationWorkflowState.CANCELLED);
            List<String> releasedStates = jdbc.queryForList("""
                    SELECT command_state
                    FROM order_inventory_location_command
                    WHERE workflow_id = ?
                    ORDER BY fulfillment_location_id
                    """, String.class, workflowId);
            assertThat(releasedStates).containsExactly("RELEASED", "RELEASED");
            assertThat(INVENTORY_A.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
            assertThat(INVENTORY_B.takeRequest(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
        }
    }

    @Test
    void internalCoordinatorRouteRequiresItsServiceToken() throws Exception {
        mockMvc.perform(get("/api/internal/order-inventory/reservations/{workflowId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String endpointConfiguration() {
        return LOCATION_A + "=" + baseUrl(INVENTORY_A) + ";" + LOCATION_B + "=" + baseUrl(INVENTORY_B);
    }

    private static String baseUrl(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private LocationReservationRequest request(UUID orderId, LocationAllocation... allocations) {
        return new LocationReservationRequest(orderId, List.of(allocations));
    }

    private LocationAllocation allocation(UUID locationId, String skuId) {
        return new LocationAllocation(locationId, List.of(new Item(SELLER_ID, skuId, 1)));
    }

    private MockResponse reserved(UUID reservationId) {
        return reserved(reservationId, Instant.now().plusSeconds(120));
    }

    private MockResponse reserved(UUID reservationId, Instant expiresAt) {
        return new MockResponse().setResponseCode(201)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\"RESERVED\","
                        + "\"expiresAt\":\"" + expiresAt + "\"}");
    }

    private MockResponse cancelled(UUID reservationId) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\"CANCELLED\"}");
    }

    private void drain(MockWebServer server) throws InterruptedException {
        while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // 이전 테스트에서 소비되지 않은 요청을 제거한다.
        }
    }
}
