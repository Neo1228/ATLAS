package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.dto.request.LocationReservationRequest;
import com.atlas.order.dto.request.LocationReservationRequest.Item;
import com.atlas.order.dto.request.LocationReservationRequest.LocationAllocation;
import com.atlas.order.dto.response.LocationReservationResponse;
import com.atlas.order.service.LocationReservationService.ClaimOutcome;
import com.atlas.order.service.impl.LocationReservationServiceImpl;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청 유실, 응답 유실, 5xx가 섞여도 주문 쪽 workflow 상태와 location 쪽 예약이 어긋나지 않고 수렴하는지 확인한다.
 *
 * <p>location은 실제 서비스가 아니라 계약(멱등 키, 취소 tombstone, 상태 전이)을 따르는 메모리 모델이다.
 * 따라서 이 테스트가 증명하는 범위는 "계약을 지키는 location을 상대로 한 coordinator의 복구"이며,
 * 실제 location 서비스가 그 계약을 지키는지는 InventoryReservationIntegrationTest가 따로 확인한다.
 */
@Testcontainers
@SpringBootTest
class LocationFaultConvergenceIntegrationTest extends PayPalIntegrationTestSupport {
    private static final UUID LOCATION_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final String SERVICE_TOKEN = "integration-coordinator-service-token-32bytes";
    private static final int ORDERS = 100;
    private static final int STOCK_PER_LOCATION = 40;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<UUID, String> INTENTS = new ConcurrentHashMap<>();
    private static final LocationModel MODEL_A = new LocationModel("sku-a", STOCK_PER_LOCATION);
    private static final LocationModel MODEL_B = new LocationModel("sku-b", STOCK_PER_LOCATION);
    private static final MockWebServer INVENTORY_A = startServer(MODEL_A);
    private static final MockWebServer INVENTORY_B = startServer(MODEL_B);

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
        properties.add("atlas.order-inventory-reservation.location-endpoints",
                () -> LOCATION_A + "=" + baseUrl(INVENTORY_A) + ";" + LOCATION_B + "=" + baseUrl(INVENTORY_B));
        properties.add("atlas.order-inventory-reservation.read-timeout", () -> "PT3S");
        properties.add("atlas.order-inventory-reservation.recovery.base-backoff", () -> "PT0.02S");
        properties.add("atlas.order-inventory-reservation.recovery.max-backoff", () -> "PT0.1S");
    }

    @Autowired
    private LocationReservationServiceImpl coordinator;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void shutdownInventoryStubs() throws IOException {
        INVENTORY_A.shutdown();
        INVENTORY_B.shutdown();
    }

    @BeforeEach
    void resetState() {
        MODEL_A.ackLostRequests = false;
        MODEL_B.ackLostRequests = false;
        clearData();
    }

    private void clearData() {
        jdbc.update("DELETE FROM order_inventory_location_item");
        jdbc.update("DELETE FROM order_inventory_location_command");
        jdbc.update("DELETE FROM order_inventory_reservation_workflow");
        MODEL_A.reset();
        MODEL_B.reset();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {11, 23, 47, 71, 97})
    void everyWorkflowConvergesAndOrderAndLocationAgreeAfterFaultsStop(long seed) throws Exception {
        Outcome outcome = runStorm(seed);

        assertThat(outcome.thrown()).as("submit/claim/cancel must report unknown outcomes, not throw").isZero();
        assertThat(countInFlight()).as("no workflow is left in flight after faults stop").isZero();
        assertThat(outcome.before().dangling()).as("unflagged: order holds stock the location does not").isZero();
        assertThat(outcome.before().orphanHolds()).as("unflagged: location holds stock no order owns").isZero();
        assertThat(outcome.after().needsReview()).as("operator release closes every flagged workflow").isZero();
        assertThat(outcome.after().dangling()).as("after operator: order holds stock the location does not").isZero();
        assertThat(outcome.after().orphanHolds()).as("after operator: location holds stock no order owns").isZero();
        assertThat(MODEL_A.conservationViolations() + MODEL_B.conservationViolations())
                .as("available + reserved + claimed equals initial stock").isZero();
    }

    /**
     * 음성 대조군. 취소가 location에 적용되지 않았는데 성공 응답을 받는 결함을 일부러 주입하면 감사가 이를 잡아야 한다.
     * 이 대조군이 없으면 위 테스트가 통과해도 감사가 결함을 볼 수 있는지 알 수 없다.
     */
    @Test
    void auditDetectsCancelAcknowledgedButNeverApplied() throws Exception {
        MODEL_A.ackLostRequests = true;
        MODEL_B.ackLostRequests = true;
        int detected = 0;
        for (long seed : new long[] {11, 23, 47, 71, 97}) {
            detected += runStorm(seed).before().orphanHolds();
        }
        System.out.println("FAULT_CONVERGENCE_CONTROL detected_orphan_holds=" + detected);
        assertThat(detected).as("the audit must detect stock held for an acknowledged-but-unapplied cancel")
                .isPositive();
    }

    /**
     * location 하나가 coordinator의 재시도 한도보다 오래 내려가 있으면 workflow는 조용히 사라지지 않고
     * NEEDS_REVIEW로 식별돼야 하고, 복구 뒤 운영자 해제로 재고가 모두 돌아와야 한다.
     */
    @Test
    void persistentOutageIsFlaggedNeedsReviewAndOperatorReleaseRestoresStock() throws Exception {
        int orders = 30;
        MODEL_B.faults.down(true);
        Map<String, UUID> orderByKey = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String key = "outage-" + i;
            keys.add(key);
            orderByKey.put(key, UUID.randomUUID());
        }
        runConcurrently(keys, key -> coordinator.submit(key, request(orderByKey.get(key))));
        Map<String, UUID> workflowByKey = workflowIds();
        int inFlightAtStart = countInFlight();

        long startedAt = System.nanoTime();
        int rounds = 0;
        while (rounds < 400 && countState("NEEDS_REVIEW") < orders) {
            rounds++;
            coordinator.recoverDue(200);
            Thread.sleep(25);
        }
        long flagMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        int flagged = countState("NEEDS_REVIEW");
        int heldAtFlag = MODEL_A.heldQuantity();
        Audit duringOutage = audit(orderByKey, workflowByKey);

        MODEL_B.faults.down(false);
        startedAt = System.nanoTime();
        for (UUID workflowId : needsReviewIds()) {
            coordinator.releaseNeedsReview(workflowId);
        }
        int releaseRounds = 0;
        while (releaseRounds < 400 && countInFlight() > 0) {
            releaseRounds++;
            coordinator.recoverDue(200);
            Thread.sleep(25);
        }
        long releaseMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        Audit afterRelease = audit(orderByKey, workflowByKey);

        String summary = "orders=%d in_flight_at_submit_return=%d flagged=%d rounds_to_flag=%d ms_to_flag=%d "
                .formatted(orders, inFlightAtStart, flagged, rounds, flagMillis)
                + "stock_held_at_flag=%d during(dangling=%d,orphan=%d) after_release(rounds=%d,ms=%d,states=%s,held=%d,"
                        .formatted(heldAtFlag, duringOutage.dangling(), duringOutage.orphanHolds(), releaseRounds,
                                releaseMillis, afterRelease.states(), MODEL_A.heldQuantity())
                + "available_a=%d/%d)".formatted(MODEL_A.availableQuantity(), STOCK_PER_LOCATION);
        System.out.println("FAULT_CONVERGENCE_OUTAGE " + summary);
        Path out = Path.of("build", "fault-convergence");
        Files.createDirectories(out);
        Files.writeString(out.resolve("outage.txt"), summary + System.lineSeparator());

        assertThat(flagged).as("every workflow behind the outage is flagged, none silently lost").isEqualTo(orders);
        assertThat(duringOutage.dangling() + duringOutage.orphanHolds())
                .as("flagged workflows own the stock they hold").isZero();
        assertThat(heldAtFlag).as("location A keeps the stock until an operator acts").isEqualTo(orders);
        assertThat(afterRelease.needsReview()).isZero();
        assertThat(afterRelease.orphanHolds()).isZero();
        assertThat(MODEL_A.heldQuantity()).as("operator release returns all stock").isZero();
        assertThat(MODEL_A.availableQuantity()).isEqualTo(STOCK_PER_LOCATION);
        assertThat(MODEL_B.availableQuantity()).isEqualTo(STOCK_PER_LOCATION);
    }

    /**
     * 같은 workflow에 claim과 cancel(그리고 중복 cancel)을 동시에 보낸다. 어느 쪽이 먼저 이기든 두 쪽 상태가
     * 어긋나지 않고, 재고가 한 번만 움직이며, claim이 이긴 경우에도 cancel이 재고를 되돌려야 한다.
     * 기존 테스트는 이 호출들을 순차로만 확인했다. faulty=true이면 장애 폭풍을 함께 섞는다.
     */
    @ParameterizedTest(name = "faulty={0}")
    @ValueSource(booleans = {false, true})
    void concurrentClaimAndCancelOnTheSameWorkflowNeverDiverge(boolean faulty) throws Exception {
        int orders = 40;
        Map<String, UUID> orderByKey = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String key = "race-" + faulty + "-" + i;
            keys.add(key);
            orderByKey.put(key, UUID.randomUUID());
        }
        runConcurrently(keys, key -> coordinator.submit(key, request(orderByKey.get(key))));
        Map<String, UUID> workflowByKey = workflowIds();
        assertThat(countState("RESERVED")).as("all submitted before the race starts").isEqualTo(orders);

        if (faulty) {
            MODEL_A.faults.start(901);
            MODEL_B.faults.start(902);
        }
        List<RaceCall> attempts = new ArrayList<>();
        for (UUID workflowId : workflowByKey.values()) {
            attempts.add(new RaceCall(workflowId, true));
            attempts.add(new RaceCall(workflowId, false));
            attempts.add(new RaceCall(workflowId, false));
        }
        Collections.shuffle(attempts, new Random(7));
        AtomicInteger thrown = new AtomicInteger();
        Map<UUID, AtomicInteger> claimedOutcome = new ConcurrentHashMap<>();
        runConcurrently(attempts, call -> {
            try {
                // 같은 workflow에 claim 1건과 cancel 2건이 섞여 동시에 들어간다.
                if (call.claim()) {
                    ClaimOutcome outcome = coordinator.claim(call.workflowId());
                    if (outcome == ClaimOutcome.CLAIMED) {
                        claimedOutcome.computeIfAbsent(call.workflowId(), id -> new AtomicInteger())
                                .incrementAndGet();
                    }
                } else {
                    coordinator.cancel(call.workflowId());
                }
            } catch (RuntimeException exception) {
                thrown.incrementAndGet();
            }
        });
        MODEL_A.faults.stop();
        MODEL_B.faults.stop();

        for (int round = 0; round < 400 && countInFlight() > 0; round++) {
            coordinator.recoverDue(200);
            Thread.sleep(25);
        }
        int inFlight = countInFlight();
        Audit audit = audit(orderByKey, workflowByKey);
        for (UUID workflowId : needsReviewIds()) {
            coordinator.releaseNeedsReview(workflowId);
        }
        for (int round = 0; round < 100 && countInFlight() > 0; round++) {
            coordinator.recoverDue(200);
            Thread.sleep(25);
        }
        Audit afterOperator = audit(orderByKey, workflowByKey);

        String summary = "faulty=%s orders=%d thrown=%d in_flight_after_recovery=%d states=%s dangling=%d orphan=%d "
                .formatted(faulty, orders, thrown.get(), inFlight, audit.states(), audit.dangling(),
                        audit.orphanHolds())
                + "claim_reported_claimed=%d after_operator(dangling=%d,orphan=%d,held_a=%d,held_b=%d)".formatted(
                        claimedOutcome.size(), afterOperator.dangling(), afterOperator.orphanHolds(),
                        MODEL_A.heldQuantity(), MODEL_B.heldQuantity());
        System.out.println("FAULT_CONVERGENCE_RACE " + summary);

        assertThat(thrown.get()).as("races must not surface exceptions").isZero();
        assertThat(inFlight).as("no workflow is stuck in flight").isZero();
        assertThat(audit.dangling()).as("order holds stock the location does not").isZero();
        assertThat(audit.orphanHolds()).as("location holds stock no order owns").isZero();
        assertThat(MODEL_A.conservationViolations() + MODEL_B.conservationViolations())
                .as("stock conserved").isZero();
        assertThat(afterOperator.dangling() + afterOperator.orphanHolds()).isZero();
        // claim을 보낸 workflow가 CLAIMED로 끝났다면 cancel이 졌다는 뜻이고, 그 경우 두 location 모두 CLAIMED여야 한다.
        for (Map.Entry<String, UUID> entry : workflowByKey.entrySet()) {
            String state = stateOf(entry.getValue());
            UUID orderId = orderByKey.get(entry.getKey());
            String a = MODEL_A.statusOf(orderId);
            String b = MODEL_B.statusOf(orderId);
            if ("CLAIMED".equals(state)) {
                assertThat(List.of(a, b)).as("claimed workflow").containsOnly("CLAIMED");
            } else if ("CANCELLED".equals(state)) {
                assertThat(List.of(a, b)).as("cancelled workflow holds nothing")
                        .doesNotContain("RESERVED").doesNotContain("CLAIMED");
            }
        }
    }

    private Outcome runStorm(long seed) throws Exception {
        clearData();
        Random random = new Random(seed);
        MODEL_A.faults.start(seed * 2);
        MODEL_B.faults.start(seed * 2 + 1);
        Map<String, UUID> orderByKey = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            String key = "fault-" + seed + "-" + i;
            keys.add(key);
            orderByKey.put(key, UUID.randomUUID());
        }

        AtomicInteger thrown = new AtomicInteger();
        runConcurrently(keys, key -> {
            try {
                coordinator.submit(key, request(orderByKey.get(key)));
            } catch (RuntimeException exception) {
                thrown.incrementAndGet();
            }
        });

        Map<String, UUID> workflowByKey = workflowIds();
        Map<UUID, String> intent = INTENTS;
        intent.clear();
        for (UUID workflowId : workflowByKey.values()) {
            int roll = random.nextInt(10);
            intent.put(workflowId, roll < 5 ? "CLAIM" : roll < 8 ? "CANCEL" : "HOLD");
        }
        runConcurrently(new ArrayList<>(workflowByKey.values()), workflowId -> {
            try {
                String wanted = intent.get(workflowId);
                if ("CLAIM".equals(wanted) && "RESERVED".equals(stateOf(workflowId))) {
                    coordinator.claim(workflowId);
                } else if ("CANCEL".equals(wanted)) {
                    coordinator.cancel(workflowId);
                }
            } catch (RuntimeException exception) {
                thrown.incrementAndGet();
            }
        });

        int unresolvedWhenFaultsStop = countInFlight();
        int requestLost = MODEL_A.faults.requestLost() + MODEL_B.faults.requestLost();
        int responseLost = MODEL_A.faults.responseLost() + MODEL_B.faults.responseLost();
        int unavailable = MODEL_A.faults.unavailable() + MODEL_B.faults.unavailable();
        MODEL_A.faults.stop();
        MODEL_B.faults.stop();

        long startedAt = System.nanoTime();
        int rounds = 0;
        while (rounds < 400) {
            rounds++;
            coordinator.recoverDue(200);
            for (Map.Entry<UUID, String> entry : intent.entrySet()) {
                reapplyIntent(entry.getKey(), entry.getValue());
            }
            if (countInFlight() == 0 && pendingIntent(intent) == 0) break;
            Thread.sleep(25);
        }
        long convergenceMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        Audit beforeOperator = audit(orderByKey, workflowByKey);
        for (UUID workflowId : needsReviewIds()) {
            coordinator.releaseNeedsReview(workflowId);
        }
        for (int i = 0; i < 100 && countInFlight() > 0; i++) {
            coordinator.recoverDue(200);
            Thread.sleep(25);
        }
        Audit afterOperator = audit(orderByKey, workflowByKey);

        String summary = "seed=%d orders=%d storm_faults(request_lost=%d,response_lost=%d,unavailable=%d) "
                .formatted(seed, ORDERS, requestLost, responseLost, unavailable)
                + "unresolved_at_fault_stop=%d recovery_rounds=%d recovery_ms=%d ".formatted(
                        unresolvedWhenFaultsStop, rounds, convergenceMillis)
                + "needs_review=%d states=%s dangling=%d orphan_holds=%d thrown=%d after_operator(dangling=%d,orphan=%d)"
                        .formatted(beforeOperator.needsReview(), beforeOperator.states(), beforeOperator.dangling(),
                                beforeOperator.orphanHolds(), thrown.get(),
                                afterOperator.dangling(), afterOperator.orphanHolds());
        System.out.println("FAULT_CONVERGENCE " + summary);
        if (!MODEL_A.ackLostRequests) {
            Path out = Path.of("build", "fault-convergence");
            Files.createDirectories(out);
            Files.writeString(out.resolve("seed-" + seed + ".txt"), summary + System.lineSeparator());
        }
        return new Outcome(thrown.get(), beforeOperator, afterOperator);
    }

    private record Outcome(int thrown, Audit before, Audit after) {
    }

    private record RaceCall(UUID workflowId, boolean claim) {
    }

    private int countState(String state) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_inventory_reservation_workflow WHERE workflow_state = ?",
                Integer.class, state);
        return count == null ? 0 : count;
    }

    private void reapplyIntent(UUID workflowId, String wanted) {
        String state = stateOf(workflowId);
        if ("CLAIM".equals(wanted) && "RESERVED".equals(state)) {
            ClaimOutcome outcome = coordinator.claim(workflowId);
            assertThat(outcome).isNotNull();
        } else if ("CANCEL".equals(wanted) && "RESERVED".equals(state)) {
            coordinator.cancel(workflowId);
        }
    }

    private int pendingIntent(Map<UUID, String> intent) {
        int pending = 0;
        for (Map.Entry<UUID, String> entry : intent.entrySet()) {
            String state = stateOf(entry.getKey());
            if ("CLAIM".equals(entry.getValue()) && "RESERVED".equals(state)) pending++;
            if ("CANCEL".equals(entry.getValue()) && "RESERVED".equals(state)) pending++;
        }
        return pending;
    }

    private Audit audit(Map<String, UUID> orderByKey, Map<String, UUID> workflowByKey) {
        Map<String, Integer> states = new HashMap<>();
        int dangling = 0;
        int needsReview = 0;
        Set<UUID> ordersOwningHolds = new HashSet<>();
        for (Map.Entry<String, UUID> entry : workflowByKey.entrySet()) {
            String state = stateOf(entry.getValue());
            states.merge(state, 1, Integer::sum);
            UUID orderId = orderByKey.get(entry.getKey());
            String a = MODEL_A.statusOf(orderId);
            String b = MODEL_B.statusOf(orderId);
            if ("NEEDS_REVIEW".equals(state)) {
                needsReview++;
                ordersOwningHolds.add(orderId);
            } else if ("RESERVED".equals(state)) {
                ordersOwningHolds.add(orderId);
                if (!"RESERVED".equals(a) || !"RESERVED".equals(b)) dangling++;
            } else if ("CLAIMED".equals(state)) {
                ordersOwningHolds.add(orderId);
                if (!"CLAIMED".equals(a) || !"CLAIMED".equals(b)) dangling++;
            }
        }
        int orphan = MODEL_A.holdsNotOwnedBy(ordersOwningHolds) + MODEL_B.holdsNotOwnedBy(ordersOwningHolds);
        if (orphan > 0) {
            Map<UUID, String> keyByOrder = new HashMap<>();
            orderByKey.forEach((key, orderId) -> keyByOrder.put(orderId, key));
            for (LocationModel model : List.of(MODEL_A, MODEL_B)) {
                for (UUID orderId : model.orphanOrders(ordersOwningHolds)) {
                    UUID workflowId = workflowByKey.get(keyByOrder.get(orderId));
                    String commands = String.join(",", jdbc.queryForList(
                            "SELECT fulfillment_location_id || ':' || command_state FROM order_inventory_location_command"
                                    + " WHERE workflow_id = ? ORDER BY fulfillment_location_id",
                            String.class, workflowId));
                    String detail = jdbc.queryForObject(
                            "SELECT workflow_state || ' attempts=' || attempt_count"
                                    + " FROM order_inventory_reservation_workflow WHERE workflow_id = ?",
                            String.class, workflowId);
                    System.out.println("HISTORY " + model.sku + " " + model.historyOf(orderId));
                    System.out.println("HISTORY " + model.sku + " " + model.historyOf(orderId));
                    System.out.println("ORPHAN " + model.sku + "=" + model.statusOf(orderId) + " workflow="
                            + detail + " commands=" + commands + " intent=" + INTENTS.get(workflowId)
                            + " otherLocation(a=" + MODEL_A.statusOf(orderId) + ",b=" + MODEL_B.statusOf(orderId) + ")");
                }
            }
        }
        return new Audit(new TreeMap<>(states), dangling, orphan, needsReview);
    }

    private record Audit(Map<String, Integer> states, int dangling, int orphanHolds, int needsReview) {
    }

    private Map<String, UUID> workflowIds() {
        Map<String, UUID> result = new HashMap<>();
        jdbc.query("SELECT idempotency_key, workflow_id FROM order_inventory_reservation_workflow",
                rs -> {
                    result.put(rs.getString(1), rs.getObject(2, UUID.class));
                });
        return result;
    }

    private List<UUID> needsReviewIds() {
        return jdbc.queryForList(
                "SELECT workflow_id FROM order_inventory_reservation_workflow WHERE workflow_state = 'NEEDS_REVIEW'",
                UUID.class);
    }

    private String stateOf(UUID workflowId) {
        return jdbc.queryForObject(
                "SELECT workflow_state FROM order_inventory_reservation_workflow WHERE workflow_id = ?",
                String.class, workflowId);
    }

    private int countInFlight() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_inventory_reservation_workflow
                WHERE workflow_state IN ('PENDING', 'RETRY_PENDING', 'COMPENSATING',
                                         'COMPENSATION_REQUIRED', 'CANCEL_PENDING')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private static <T> void runConcurrently(List<T> items, Consumer<T> action) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            List<Future<?>> futures = new ArrayList<>();
            for (T item : items) {
                Callable<Object> task = () -> {
                    action.accept(item);
                    return null;
                };
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) future.get(120, TimeUnit.SECONDS);
        }
    }

    private LocationReservationRequest request(UUID orderId) {
        return new LocationReservationRequest(orderId, List.of(
                new LocationAllocation(LOCATION_A, List.of(new Item(SELLER_ID, "sku-a", 1))),
                new LocationAllocation(LOCATION_B, List.of(new Item(SELLER_ID, "sku-b", 1)))));
    }

    private static MockWebServer startServer(LocationModel model) {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(model);
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String baseUrl(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }

    /** 실패 주입 확률. 요청 유실은 적용하지 않고, 응답 유실은 적용한 뒤 둘 다 응답 없이 연결을 끊는다. */
    private static final class Faults {
        private final AtomicBoolean enabled = new AtomicBoolean();
        private final AtomicBoolean down = new AtomicBoolean();
        private final AtomicInteger requestLost = new AtomicInteger();
        private final AtomicInteger responseLost = new AtomicInteger();
        private final AtomicInteger unavailable = new AtomicInteger();
        private Random random = new Random(0);

        synchronized void start(long seed) {
            random = new Random(seed);
            enabled.set(true);
        }

        void stop() {
            enabled.set(false);
        }

        void down(boolean value) {
            down.set(value);
        }

        void reset() {
            enabled.set(false);
            down.set(false);
            requestLost.set(0);
            responseLost.set(0);
            unavailable.set(0);
        }

        synchronized int roll() {
            return enabled.get() ? random.nextInt(100) : 100;
        }

        int requestLost() {
            return requestLost.get();
        }

        int responseLost() {
            return responseLost.get();
        }

        int unavailable() {
            return unavailable.get();
        }
    }

    /** location 계약의 메모리 모델. 실제 서비스(InventoryReservationService)의 멱등·tombstone·전이 규칙을 따른다. */
    private static final class LocationModel extends Dispatcher {
        private record Reservation(UUID id, UUID orderId, String key, int quantity) {
        }

        private final String sku;
        private final int initialStock;
        private final Faults faults = new Faults();
        private final Map<String, Reservation> byKey = new HashMap<>();
        private final Map<UUID, Reservation> byId = new HashMap<>();
        private final Map<UUID, String> statusById = new HashMap<>();
        private final Map<UUID, String> tombstonedOrders = new HashMap<>();
        private volatile boolean ackLostRequests;
        private final List<String> history = new ArrayList<>();
        private int sequence;
        private int available;

        LocationModel(String sku, int initialStock) {
            this.sku = sku;
            this.initialStock = initialStock;
            this.available = initialStock;
        }

        synchronized void reset() {
            byKey.clear();
            byId.clear();
            statusById.clear();
            tombstonedOrders.clear();
            history.clear();
            sequence = 0;
            available = initialStock;
            faults.reset();
        }

        synchronized String statusOf(UUID orderId) {
            return byId.values().stream().filter(r -> r.orderId().equals(orderId))
                    .map(r -> statusById.get(r.id())).findFirst().orElse("NONE");
        }

        synchronized int holdsNotOwnedBy(Set<UUID> owners) {
            int orphan = 0;
            for (Reservation reservation : byId.values()) {
                String status = statusById.get(reservation.id());
                if (("RESERVED".equals(status) || "CLAIMED".equals(status))
                        && !owners.contains(reservation.orderId())) {
                    orphan++;
                }
            }
            return orphan;
        }

        synchronized List<UUID> orphanOrders(Set<UUID> owners) {
            List<UUID> orders = new ArrayList<>();
            for (Reservation reservation : byId.values()) {
                String status = statusById.get(reservation.id());
                if (("RESERVED".equals(status) || "CLAIMED".equals(status))
                        && !owners.contains(reservation.orderId())) {
                    orders.add(reservation.orderId());
                }
            }
            return orders;
        }

        synchronized int heldQuantity() {
            int held = 0;
            for (Reservation reservation : byId.values()) {
                String status = statusById.get(reservation.id());
                if ("RESERVED".equals(status) || "CLAIMED".equals(status)) held += reservation.quantity();
            }
            return held;
        }

        synchronized int availableQuantity() {
            return available;
        }

        synchronized int conservationViolations() {
            int held = 0;
            for (Reservation reservation : byId.values()) {
                String status = statusById.get(reservation.id());
                if ("RESERVED".equals(status) || "CLAIMED".equals(status)) held += reservation.quantity();
            }
            return available >= 0 && available + held == initialStock ? 0 : 1;
        }

        @Override
        public synchronized MockResponse dispatch(RecordedRequest request) {
            int roll = faults.roll();
            String operation = describe(request);
            if (faults.down.get()) {
                record(operation, "DOWN_503");
                return new MockResponse().setResponseCode(503);
            }
            if (roll < 8) {
                faults.requestLost.incrementAndGet();
                record(operation, "REQUEST_LOST");
                if (ackLostRequests) {
                    return new MockResponse();
                }
                // DISCONNECT_AT_START는 연결 수락 시점에 dispatcher.peek()로만 평가되어 dispatch()가 돌려줘도
                // 무시되고 빈 200 응답이 나간다. 요청을 읽되 적용하지 않고 응답 없이 끊는 정책으로 유실을 만든다.
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            }
            if (roll < 13) {
                faults.unavailable.incrementAndGet();
                record(operation, "HTTP_503");
                return new MockResponse().setResponseCode(503);
            }
            MockResponse response = apply(request);
            if (roll < 25) {
                faults.responseLost.incrementAndGet();
                record(operation, "APPLIED_RESPONSE_LOST http=" + response.getStatus());
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            }
            record(operation, "OK " + response.getStatus());
            return response;
        }

        private String describe(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            String[] parts = path.split("/");
            String last = parts.length == 0 ? "" : parts[parts.length - 1];
            String owner = "?";
            if ("reservations".equals(last) || "cancel".equals(last)) {
                JsonNode body = JSON.readTree(request.getBody().clone().readUtf8());
                owner = body.get("orderId").asString();
            } else if (parts.length >= 2) {
                Reservation reservation = byId.get(UUID.fromString(parts[parts.length - 2]));
                owner = reservation == null ? "unknown-reservation" : reservation.orderId().toString();
            }
            return last + " order=" + owner;
        }

        private synchronized void record(String operation, String result) {
            history.add(++sequence + ":" + operation + ":" + result);
        }

        synchronized List<String> historyOf(UUID orderId) {
            List<String> entries = new ArrayList<>();
            String needle = orderId.toString();
            for (String entry : history) {
                if (entry.contains(needle)) entries.add(entry);
            }
            return entries;
        }

        private synchronized MockResponse apply(RecordedRequest request) {
            String path = request.getPath();
            if (path == null) return error(404, "NOT_FOUND");
            String[] parts = path.split("/");
            String last = parts[parts.length - 1];
            if ("reservations".equals(last)) {
                return reserve(request);
            }
            if ("cancel".equals(last) && "reservations".equals(parts[parts.length - 2])) {
                return cancel(request);
            }
            UUID reservationId = UUID.fromString(parts[parts.length - 2]);
            return transition(reservationId, last);
        }

        private MockResponse reserve(RecordedRequest request) {
            String key = request.getHeader("Idempotency-Key");
            JsonNode body = JSON.readTree(request.getBody().readUtf8());
            UUID orderId = UUID.fromString(body.get("orderId").asString());
            int quantity = body.get("items").get(0).get("quantity").asInt();
            Reservation prior = byKey.get(key);
            if (prior != null) {
                if (!prior.orderId().equals(orderId)) return error(409, "IDEMPOTENCY_CONFLICT");
                if ("CANCELLED".equals(statusById.get(prior.id()))) return error(409, "RESERVATION_CANCELLED");
                return reservation(200, prior.id());
            }
            if (tombstonedOrders.containsKey(orderId)) return error(409, "RESERVATION_CANCELLED");
            if (available < quantity) return error(409, "INSUFFICIENT_STOCK");
            available -= quantity;
            Reservation created = new Reservation(UUID.randomUUID(), orderId, key, quantity);
            byKey.put(key, created);
            byId.put(created.id(), created);
            statusById.put(created.id(), "RESERVED");
            return reservation(201, created.id());
        }

        private MockResponse cancel(RecordedRequest request) {
            JsonNode body = JSON.readTree(request.getBody().readUtf8());
            UUID orderId = UUID.fromString(body.get("orderId").asString());
            Reservation found = byId.values().stream().filter(r -> r.orderId().equals(orderId)).findFirst().orElse(null);
            if (found == null) {
                tombstonedOrders.put(orderId, "CANCELLED");
                return new MockResponse().setResponseCode(200).addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .setBody("{\"reservationId\":\"" + UUID.randomUUID() + "\",\"status\":\"CANCELLED\"}");
            }
            String status = statusById.get(found.id());
            if ("RESERVED".equals(status)) {
                available += found.quantity();
                statusById.put(found.id(), "CANCELLED");
            } else if ("CLAIMED".equals(status)) {
                return error(409, "INVALID_STATE");
            }
            return reservation(200, found.id());
        }

        private MockResponse transition(UUID reservationId, String operation) {
            Reservation reservation = byId.get(reservationId);
            if (reservation == null) return error(404, "NOT_FOUND");
            String status = statusById.get(reservationId);
            switch (operation) {
                case "claim" -> {
                    if ("CANCELLED".equals(status)) return error(409, "RESERVATION_CANCELLED");
                    if ("RESERVED".equals(status)) statusById.put(reservationId, "CLAIMED");
                    else if (!"CLAIMED".equals(status)) return error(409, "INVALID_STATE");
                }
                case "release" -> {
                    if ("RESERVED".equals(status)) {
                        available += reservation.quantity();
                        statusById.put(reservationId, "RELEASED");
                    } else if ("CLAIMED".equals(status)) {
                        return error(409, "INVALID_STATE");
                    }
                }
                case "restore" -> {
                    if ("CLAIMED".equals(status)) {
                        available += reservation.quantity();
                        statusById.put(reservationId, "RELEASED");
                    } else if (!"RELEASED".equals(status)) {
                        return error(409, "INVALID_STATE");
                    }
                }
                default -> {
                    return error(404, "NOT_FOUND");
                }
            }
            return reservation(200, reservationId);
        }

        private MockResponse reservation(int code, UUID reservationId) {
            return new MockResponse().setResponseCode(code)
                    .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\""
                            + statusById.get(reservationId) + "\",\"expiresAt\":\""
                            + Instant.now().plusSeconds(600) + "\"}");
        }

        private MockResponse error(int code, String errorCode) {
            return new MockResponse().setResponseCode(code)
                    .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .setBody("{\"code\":\"" + errorCode + "\",\"message\":\"" + errorCode + "\"}");
        }
    }
}
