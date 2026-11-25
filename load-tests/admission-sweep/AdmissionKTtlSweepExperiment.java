package com.atlas.experiment;

import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.model.TicketStatus;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.OrderService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Array;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * EXP-086: hot SKU 한 개에 구매자 N명이 몰릴 때 K(checkout slot 수)와 Campaign ticket TTL이
 * 접수 주문 수, 대기 만료, slot 낭비, 순번 위반, 재고 불변식에 주는 영향을 센다.
 *
 * <p>실제 AdmissionService, OrderService(재고 예약, 결제 제출), PostgreSQL을 쓰고 시간만 가상 시계로
 * 돌린다. 구매자 행동은 시드가 같으면 모든 (K, TTL) 칸에서 같은 난수열이라 칸끼리 짝 비교가 된다.
 * 일반 {@code check}에 넣지 않는다: {@code load-tests/admission-sweep/run.sh}가 임시로 복사해 실행한다.
 */
@SpringBootTest
@Testcontainers
class AdmissionKTtlSweepExperiment extends PayPalIntegrationTestSupport {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withLabel("atlasexp", "c2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.payment.paypal.webhook-retry.worker.enabled", () -> "false");
    }

    /** 구매자 모형 상수. 값과 근거는 EXP-086 문서에 적는다. */
    private static final double ABANDON_BEFORE_START = Double.parseDouble(env("SWEEP_ABANDON_BEFORE", "0.10"));
    private static final double ABANDON_AFTER_START = Double.parseDouble(env("SWEEP_ABANDON_AFTER", "0.20"));
    private static final double REJOIN_PROBABILITY = 0.70;
    private static final int MAX_REJOINS = 2;
    private static final double THINK_START_MEDIAN_S = 10.0;
    private static final double THINK_PAY_MEDIAN_S = 30.0;
    private static final double THINK_SIGMA = 0.6;
    private static final long POLL_MS = 2_000;
    private static final long TICK_MS = 1_000;
    private static final long ORDER_WINDOW_MS = 600_000;
    private static final long HORIZON_MS = 45 * 60_000L;

    @Autowired private AdmissionService admission;
    @Autowired private CampaignService campaigns;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private OrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdjustableClock clock;

    private enum Outcome {
        ACCEPTED, SOLD_OUT, ABANDONED_BEFORE_START, ABANDONED_AFTER_START, LOST_QUEUE_EXPIRED,
        LOST_SLOW_START, LOST_SLOW_SUBMIT, ERROR, UNRESOLVED
    }

    /** slot 보유 구간이 끝난 이유. */
    private enum SlotEnd { ACCEPTED, SOLD_OUT, ABANDONED_AFTER_START, ABANDONED_BEFORE_START, SLOW_START, SLOW_SUBMIT }

    private enum Kind { JOIN, START, SUBMIT, TICK }

    private record Event(long timeMs, long order, Kind kind, int buyer) { }

    private static final class Buyer {
        final int index;
        final String ref;
        final long arrivalMs;
        final boolean abandonBeforeStart;
        final boolean abandonAfterStart;
        final long thinkStartMs;
        final long thinkPayMs;
        final boolean[] rejoin;
        final SplittableRandom noticeRandom;
        int attempts;
        int rejoinsLeft = MAX_REJOINS;
        long firstJoinMs = -1;
        long slotAcquiredMs = -1;
        UUID ticketId;
        UUID orderId;
        Outcome outcome;
        long finalMs;
        final double[] slotSecondsByEnd = new double[SlotEnd.values().length];

        Buyer(int index, long seed, long arrivalMs, SplittableRandom random) {
            this.index = index;
            this.ref = "buyer-" + index;
            this.arrivalMs = arrivalMs;
            this.abandonBeforeStart = random.nextDouble() < ABANDON_BEFORE_START;
            this.abandonAfterStart = random.nextDouble() < ABANDON_AFTER_START;
            this.thinkStartMs = lognormalMs(random, THINK_START_MEDIAN_S);
            this.thinkPayMs = lognormalMs(random, THINK_PAY_MEDIAN_S);
            this.rejoin = new boolean[MAX_REJOINS];
            for (int i = 0; i < MAX_REJOINS; i++) {
                rejoin[i] = random.nextDouble() < REJOIN_PROBABILITY;
            }
            this.noticeRandom = new SplittableRandom(seed * 1_000_003L + index);
        }
    }

    private static long lognormalMs(SplittableRandom random, double medianSeconds) {
        double u1 = Math.max(1e-12, random.nextDouble());
        double u2 = random.nextDouble();
        double normal = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
        return Math.round(medianSeconds * Math.exp(THINK_SIGMA * normal) * 1000.0);
    }

    @Test
    void sweep() throws Exception {
        List<Integer> ks = ints(env("SWEEP_KS", "5,10,20,50"));
        List<Integer> ttls = ints(env("SWEEP_TTLS", "30,60,120"));
        List<Integer> seeds = ints(env("SWEEP_SEEDS", "1,2,3"));
        int buyers = Integer.parseInt(env("SWEEP_BUYERS", "300"));
        int stock = Integer.parseInt(env("SWEEP_STOCK", "60"));
        int arrivalSeconds = Integer.parseInt(env("SWEEP_ARRIVAL_SECONDS", "30"));
        Path out = Path.of(env("SWEEP_OUT", "/tmp/atlas-c2-results/sweep.jsonl"));
        Files.createDirectories(out.getParent());
        for (int seed : seeds) {
            for (int k : ks) {
                for (int ttl : ttls) {
                    String line = runCell(k, ttl, seed, buyers, stock, arrivalSeconds);
                    Files.writeString(out, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    System.out.println("SWEEP_CELL " + line);
                }
            }
        }
    }

    private String runCell(int k, int ttlSeconds, int seed, int buyerCount, int stock, int arrivalSeconds)
            throws Exception {
        resetPayPalStubState();
        long wallStart = System.nanoTime();
        Instant base = Instant.now();
        clock.set(base);
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "sweep-" + UUID.randomUUID();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("sweep " + skuId, null));
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 17_200, stock));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, k));
        UUID campaignId = campaigns.open(campaigns.create(new AdmissionRequest.CreateCampaign(
                sellerId, skuId, Duration.ofSeconds(ttlSeconds))).campaignId()).campaignId();

        Buyer[] crowd = new Buyer[buyerCount];
        long[] arrivals = new long[buyerCount];
        SplittableRandom arrivalRandom = new SplittableRandom(seed);
        for (int i = 0; i < buyerCount; i++) {
            arrivals[i] = Math.round(arrivalRandom.nextDouble() * arrivalSeconds * 1000.0);
        }
        Arrays.sort(arrivals);
        for (int i = 0; i < buyerCount; i++) {
            crowd[i] = new Buyer(i, seed, arrivals[i], new SplittableRandom(seed * 7_919L + i));
        }

        PriorityQueue<Event> queue = new PriorityQueue<>((a, b) -> a.timeMs() != b.timeMs()
                ? Long.compare(a.timeMs(), b.timeMs()) : Long.compare(a.order(), b.order()));
        long[] sequence = {0};
        for (Buyer buyer : crowd) {
            queue.add(new Event(buyer.arrivalMs, sequence[0]++, Kind.JOIN, buyer.index));
        }
        queue.add(new Event(0, sequence[0]++, Kind.TICK, -1));

        Map<UUID, Buyer> waiting = new HashMap<>();
        Map<UUID, Buyer> holding = new HashMap<>();
        int unresolved = buyerCount;
        int maxSlotsHeld = 0;
        long lastOrderExpiryMs = 0;
        int errors = 0;
        Map<String, Integer> errorKinds = new HashMap<>();
        long now = 0;
        while (!queue.isEmpty()) {
            Event event = queue.poll();
            if (event.timeMs() > HORIZON_MS) {
                break;
            }
            now = event.timeMs();
            clock.set(base.plusMillis(now));
            Buyer buyer = event.buyer() >= 0 ? crowd[event.buyer()] : null;
            try {
                switch (event.kind()) {
                    case JOIN -> {
                        AdmissionResponse.QueueTicket ticket = admission.join(new AdmissionRequest.JoinQueue(
                                campaignId, skuId, buyer.ref, buyer.ref + "-" + buyer.attempts++));
                        if (buyer.firstJoinMs < 0) {
                            buyer.firstJoinMs = now;
                        }
                        buyer.ticketId = ticket.ticketId();
                        if (ticket.checkoutSlotHeld()) {
                            holdSlot(queue, sequence, holding, buyer, now, now, true);
                        } else {
                            waiting.put(ticket.ticketId(), buyer);
                        }
                    }
                    case START -> {
                        OrderViewResponse.Order order = orders.startCheckout(campaignId, buyer.ticketId);
                        holding.remove(buyer.ticketId);
                        if (order.state() == OrderState.RESERVED) {
                            buyer.orderId = order.orderId();
                            if (buyer.abandonAfterStart) {
                                finish(buyer, Outcome.ABANDONED_AFTER_START, now);
                                endSlot(buyer, now + ORDER_WINDOW_MS, SlotEnd.ABANDONED_AFTER_START);
                                lastOrderExpiryMs = Math.max(lastOrderExpiryMs, now + ORDER_WINDOW_MS);
                                unresolved--;
                            } else {
                                queue.add(new Event(now + buyer.thinkPayMs, sequence[0]++, Kind.SUBMIT, buyer.index));
                            }
                        } else {
                            finish(buyer, Outcome.SOLD_OUT, now);
                            endSlot(buyer, now, SlotEnd.SOLD_OUT);
                            unresolved--;
                        }
                    }
                    case SUBMIT -> {
                        OrderViewResponse.Order order = orders.submitPayment(buyer.orderId);
                        if (order.state() == OrderState.PAYMENT_PENDING || order.state() == OrderState.PAYMENT_SUBMITTED
                                || order.state() == OrderState.PAID) {
                            finish(buyer, Outcome.ACCEPTED, now);
                            endSlot(buyer, now, SlotEnd.ACCEPTED);
                        } else {
                            finish(buyer, Outcome.LOST_SLOW_SUBMIT, now);
                            endSlot(buyer, now, SlotEnd.SLOW_SUBMIT);
                        }
                        unresolved--;
                    }
                    case TICK -> {
                        admission.expireDueTickets();
                        orders.expireDueOrders();
                        maxSlotsHeld = Math.max(maxSlotsHeld, slotsHeld(sellerId, skuId));
                        unresolved -= observeTickets(campaignId, queue, sequence, waiting, holding, now, ttlSeconds * 1000L);
                        boolean more = unresolved > 0 || now < lastOrderExpiryMs + TICK_MS;
                        if (more) {
                            queue.add(new Event(now + TICK_MS, sequence[0]++, Kind.TICK, -1));
                        }
                    }
                    default -> throw new IllegalStateException();
                }
            } catch (AdmissionException exception) {
                holding.remove(buyer == null ? null : buyer.ticketId);
                if (buyer != null && event.kind() == Kind.START) {
                    endSlot(buyer, buyer.slotAcquiredMs + ttlSeconds * 1000L, SlotEnd.SLOW_START);
                    if (!rejoin(queue, sequence, buyer, now)) {
                        finish(buyer, Outcome.LOST_SLOW_START, now);
                        unresolved--;
                    }
                } else {
                    errors++;
                    errorKinds.merge("Admission:" + exception.code(), 1, Integer::sum);
                    if (buyer != null && buyer.outcome == null) {
                        finish(buyer, Outcome.ERROR, now);
                        unresolved--;
                    }
                }
            } catch (RuntimeException exception) {
                errors++;
                errorKinds.merge(exception.getClass().getSimpleName() + ":" + event.kind(), 1, Integer::sum);
                if (buyer != null && buyer.outcome == null) {
                    finish(buyer, Outcome.ERROR, now);
                    unresolved--;
                }
            }
        }
        for (Buyer buyer : crowd) {
            if (buyer.outcome == null) {
                finish(buyer, Outcome.UNRESOLVED, now);
            }
        }
        return summarize(k, ttlSeconds, seed, buyerCount, stock, sellerId, skuId, campaignId, crowd,
                maxSlotsHeld, errors, errorKinds, now, (System.nanoTime() - wallStart) / 1_000_000_000.0);
    }

    private void endSlot(Buyer buyer, long endMs, SlotEnd reason) {
        buyer.slotSecondsByEnd[reason.ordinal()] += Math.max(0, endMs - buyer.slotAcquiredMs) / 1000.0;
    }

    private void finish(Buyer buyer, Outcome outcome, long nowMs) {
        buyer.outcome = outcome;
        buyer.finalMs = nowMs;
    }

    private void holdSlot(PriorityQueue<Event> queue, long[] sequence, Map<UUID, Buyer> holding, Buyer buyer,
            long acquiredMs, long nowMs, boolean noticedAtOnce) {
        buyer.slotAcquiredMs = acquiredMs;
        holding.put(buyer.ticketId, buyer);
        if (!buyer.abandonBeforeStart) {
            long noticedMs = noticedAtOnce ? nowMs : Math.max(nowMs, acquiredMs + buyer.noticeRandom.nextLong(POLL_MS));
            queue.add(new Event(noticedMs + buyer.thinkStartMs, sequence[0]++, Kind.START, buyer.index));
        }
    }

    /** ticket이 만료된 구매자가 새 key로 다시 줄을 서는지 모형 난수로 정하고, 서면 JOIN 이벤트를 넣는다. */
    private boolean rejoin(PriorityQueue<Event> queue, long[] sequence, Buyer buyer, long nowMs) {
        if (buyer.rejoinsLeft > 0 && buyer.rejoin[MAX_REJOINS - buyer.rejoinsLeft]) {
            buyer.rejoinsLeft--;
            queue.add(new Event(nowMs + buyer.noticeRandom.nextLong(POLL_MS) + 1_000, sequence[0]++,
                    Kind.JOIN, buyer.index));
            return true;
        }
        buyer.rejoinsLeft = 0;
        return false;
    }

    /** 이번 tick에서 확인된 상태 변화를 반영하고, 새로 확정된 구매자 수를 돌려준다. */
    private int observeTickets(UUID campaignId, PriorityQueue<Event> queue, long[] sequence,
            Map<UUID, Buyer> waiting, Map<UUID, Buyer> holding, long nowMs, long ttlMs) {
        int finished = 0;
        Instant base = clock.instant().minusMillis(nowMs);
        if (!waiting.isEmpty()) {
            List<UUID> ids = new ArrayList<>(waiting.keySet());
            List<Map<String, Object>> rows = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT ticket_id, status, checkout_slot_acquired_at "
                        + "FROM admission_ticket WHERE ticket_id = ANY (?) AND status IN ('CHECKOUT_ACTIVE', 'EXPIRED')");
                Array array = connection.createArrayOf("uuid", ids.toArray());
                statement.setArray(1, array);
                return statement;
            }, (result, row) -> Map.of(
                    "id", result.getObject("ticket_id", UUID.class),
                    "status", result.getString("status"),
                    "acquired", result.getTimestamp("checkout_slot_acquired_at") == null
                            ? Instant.EPOCH : result.getTimestamp("checkout_slot_acquired_at").toInstant()));
            for (Map<String, Object> row : rows) {
                UUID id = (UUID) row.get("id");
                Buyer buyer = waiting.remove(id);
                if ("CHECKOUT_ACTIVE".equals(row.get("status"))) {
                    long acquiredMs = Duration.between(base, (Instant) row.get("acquired")).toMillis();
                    holdSlot(queue, sequence, holding, buyer, acquiredMs, nowMs, false);
                } else if (!rejoin(queue, sequence, buyer, nowMs)) {
                    finish(buyer, Outcome.LOST_QUEUE_EXPIRED, nowMs);
                    finished++;
                }
            }
        }
        if (!holding.isEmpty()) {
            List<UUID> ids = new ArrayList<>(holding.keySet());
            List<UUID> expired = jdbc.query(connection -> {
                var statement = connection.prepareStatement(
                        "SELECT ticket_id FROM admission_ticket WHERE ticket_id = ANY (?) AND status = 'EXPIRED'");
                statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
                return statement;
            }, (result, row) -> result.getObject("ticket_id", UUID.class));
            for (UUID id : expired) {
                Buyer buyer = holding.get(id);
                if (buyer.abandonBeforeStart) {
                    holding.remove(id);
                    finish(buyer, Outcome.ABANDONED_BEFORE_START, nowMs);
                    endSlot(buyer, buyer.slotAcquiredMs + ttlMs, SlotEnd.ABANDONED_BEFORE_START);
                    finished++;
                }
                // 시작하려던 구매자는 START 이벤트가 실제 서비스 오류로 확정한다(LOST_SLOW_START).
            }
        }
        return finished;
    }

    private int slotsHeld(UUID sellerId, String skuId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM admission_ticket WHERE seller_id = ? AND sku_id = ? "
                + "AND checkout_slot_held", Integer.class, sellerId, skuId);
        return count == null ? 0 : count;
    }

    private String summarize(int k, int ttlSeconds, int seed, int buyerCount, int stock, UUID sellerId, String skuId,
            UUID campaignId, Buyer[] crowd, int maxSlotsHeld, int errors, Map<String, Integer> errorKinds,
            long endMs, double wallSeconds) {
        Map<Outcome, Integer> byOutcome = new HashMap<>();
        List<Double> waitAccepted = new ArrayList<>();
        List<Double> toAccepted = new ArrayList<>();
        double[] slotSecondsByEnd = new double[SlotEnd.values().length];
        int falseExpiry = 0;
        int rejoins = 0;
        for (Buyer buyer : crowd) {
            byOutcome.merge(buyer.outcome, 1, Integer::sum);
            rejoins += buyer.attempts > 0 ? buyer.attempts - 1 : 0;
            if (buyer.outcome == Outcome.ACCEPTED) {
                waitAccepted.add((buyer.slotAcquiredMs - buyer.firstJoinMs) / 1000.0);
                toAccepted.add((buyer.finalMs - buyer.firstJoinMs) / 1000.0);
            }
            if (buyer.outcome == Outcome.LOST_QUEUE_EXPIRED || buyer.outcome == Outcome.LOST_SLOW_START) {
                falseExpiry++;
            }
            for (int i = 0; i < slotSecondsByEnd.length; i++) {
                slotSecondsByEnd[i] += buyer.slotSecondsByEnd[i];
            }
        }
        Integer expiredWaiting = jdbc.queryForObject("SELECT count(*) FROM admission_ticket WHERE campaign_id = ? "
                + "AND status = 'EXPIRED' AND checkout_slot_acquired_at IS NULL", Integer.class, campaignId);
        Integer expiredHolding = jdbc.queryForObject("SELECT count(*) FROM admission_ticket WHERE campaign_id = ? "
                + "AND status = 'EXPIRED' AND checkout_slot_acquired_at IS NOT NULL", Integer.class, campaignId);
        Integer tickets = jdbc.queryForObject(
                "SELECT count(*) FROM admission_ticket WHERE campaign_id = ?", Integer.class, campaignId);
        // 순번 위반: 순번이 더 작은 ticket이 아직 대기 중인데(만료 전) 큰 순번이 먼저 slot을 얻은 쌍.
        Integer orderViolations = jdbc.queryForObject("""
                SELECT count(*) FROM admission_ticket later
                JOIN admission_ticket earlier ON earlier.campaign_id = later.campaign_id
                  AND earlier.sequence < later.sequence
                WHERE later.campaign_id = ? AND later.checkout_slot_acquired_at IS NOT NULL
                  AND earlier.created_at <= later.checkout_slot_acquired_at
                  AND (earlier.checkout_slot_acquired_at IS NULL OR earlier.checkout_slot_acquired_at > later.checkout_slot_acquired_at)
                  AND (earlier.status <> 'EXPIRED' OR earlier.checkout_slot_acquired_at IS NOT NULL
                       OR earlier.expires_at > later.checkout_slot_acquired_at)
                """, Integer.class, campaignId);
        Map<String, Object> pool = jdbc.queryForMap("SELECT initial_capacity, available, reserved, claimed "
                + "FROM inventory_pool WHERE seller_id = ? AND sku_id = ?", sellerId, skuId);
        Integer liveReservations = jdbc.queryForObject("SELECT count(*) FROM inventory_reservation "
                + "WHERE seller_id = ? AND sku_id = ? AND status IN ('RESERVED', 'CLAIMED')",
                Integer.class, sellerId, skuId);
        int accepted = byOutcome.getOrDefault(Outcome.ACCEPTED, 0);
        int reservedPlusClaimed = ((Number) pool.get("reserved")).intValue() + ((Number) pool.get("claimed")).intValue();
        int oversell = Math.max(0, Math.max(reservedPlusClaimed, liveReservations) - stock)
                + (((Number) pool.get("available")).intValue() < 0 ? 1 : 0);
        StringBuilder json = new StringBuilder("{");
        json.append("\"k\":").append(k).append(",\"ttlSeconds\":").append(ttlSeconds)
                .append(",\"seed\":").append(seed).append(",\"buyers\":").append(buyerCount)
                .append(",\"stock\":").append(stock)
                .append(",\"abandonBefore\":").append(ABANDON_BEFORE_START)
                .append(",\"abandonAfter\":").append(ABANDON_AFTER_START);
        for (Outcome outcome : Outcome.values()) {
            json.append(",\"").append(outcome.name().toLowerCase()).append("\":").append(byOutcome.getOrDefault(outcome, 0));
        }
        json.append(",\"falseExpiryLost\":").append(falseExpiry)
                .append(",\"rejoins\":").append(rejoins)
                .append(",\"tickets\":").append(tickets)
                .append(",\"expiredWaitingTickets\":").append(expiredWaiting)
                .append(",\"expiredHoldingTickets\":").append(expiredHolding)
                .append(",\"queueOrderViolations\":").append(orderViolations)
                .append(",\"maxSlotsHeld\":").append(maxSlotsHeld)
                .append(",\"slotLimitExceeded\":").append(maxSlotsHeld > k)
                .append(",\"oversell\":").append(oversell)
                .append(",\"poolAvailable\":").append(pool.get("available"))
                .append(",\"poolReserved\":").append(pool.get("reserved"))
                .append(",\"poolClaimed\":").append(pool.get("claimed"))
                .append(",\"liveReservations\":").append(liveReservations)
                .append(",\"slotSecondsTotal\":").append(round(Arrays.stream(slotSecondsByEnd).sum()))
                .append(",\"slotSecondsProductive\":").append(round(slotSecondsByEnd[SlotEnd.ACCEPTED.ordinal()]))
                .append(slotSecondsJson(slotSecondsByEnd))
                .append(",\"waitToSlotAcceptedP50S\":").append(percentile(waitAccepted, 0.50))
                .append(",\"waitToSlotAcceptedP95S\":").append(percentile(waitAccepted, 0.95))
                .append(",\"timeToAcceptedP50S\":").append(percentile(toAccepted, 0.50))
                .append(",\"timeToAcceptedP95S\":").append(percentile(toAccepted, 0.95))
                .append(",\"lastAcceptedVirtualS\":").append(round(toAccepted.isEmpty() ? 0 : lastAcceptedMs(crowd) / 1000.0))
                .append(",\"virtualEndS\":").append(round(endMs / 1000.0))
                .append(",\"errors\":").append(errors)
                .append(",\"errorKinds\":\"").append(errorKinds).append("\"")
                .append(",\"acceptedEqualsStockOrFewer\":").append(accepted <= stock)
                .append(",\"wallSeconds\":").append(round(wallSeconds)).append("}");
        return json.toString();
    }

    private static String slotSecondsJson(double[] byEnd) {
        StringBuilder json = new StringBuilder();
        for (SlotEnd reason : SlotEnd.values()) {
            json.append(",\"slotSec_").append(reason.name().toLowerCase()).append("\":")
                    .append(round(byEnd[reason.ordinal()]));
        }
        return json.toString();
    }

    private static long lastAcceptedMs(Buyer[] crowd) {
        long last = 0;
        for (Buyer buyer : crowd) {
            if (buyer.outcome == Outcome.ACCEPTED) {
                last = Math.max(last, buyer.finalMs);
            }
        }
        return last;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return "null";
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int index = (int) Math.min(sorted.size() - 1, Math.ceil(q * sorted.size()) - 1);
        return String.valueOf(round(sorted.get(Math.max(0, index))));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<Integer> ints(String csv) {
        List<Integer> values = new ArrayList<>();
        for (String part : csv.split(",")) {
            values.add(Integer.parseInt(part.trim()));
        }
        return values;
    }

    @TestConfiguration
    static class VirtualClockConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
