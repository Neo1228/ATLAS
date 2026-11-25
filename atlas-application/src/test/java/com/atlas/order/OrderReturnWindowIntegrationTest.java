package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.PostgresLockWaitProbe;
import com.atlas.application.service.ReturnInspectionMetrics;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.ReturnResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.repository.OrderFulfillmentRepository;
import com.atlas.order.repository.OrderItemRepository;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.repository.OrderRepository;
import com.atlas.order.repository.OrderReturnRepository;
import com.atlas.order.repository.OrderShipmentRepository;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderReturnService;
import com.atlas.order.service.OrderShipmentService;
import com.atlas.order.service.ReturnPolicy;
import com.atlas.order.service.impl.OrderReturnServiceImpl;
import com.atlas.payment.PayPalIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 반품 기간(7일)과 검수 마감(5영업일)이 코드로 강제되는지 실제 PostgreSQL과 조정 가능한 Clock으로 확인한다
 * (ADR-0089, EXP-093). 시간은 모두 시험이 {@link AdjustableClock}에 지정한 값이고 실제 시계에 기대지 않는다.
 */
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
@Import(OrderReturnWindowIntegrationTest.ClockConfiguration.class)
class OrderReturnWindowIntegrationTest extends PayPalIntegrationTestSupport {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas")
            .withLabel("atlasexp", "h3");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private OrderFulfillmentService fulfillments;
    @Autowired private OrderShipmentService shipments;
    @Autowired private OrderReturnService returns;
    @Autowired private OrderRefundService refunds;
    @Autowired private ReturnPolicy policy;
    @Autowired private ReturnInspectionMetrics inspectionMetrics;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;
    @Autowired private AdjustableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository itemRepository;
    @Autowired private OrderFulfillmentRepository fulfillmentRepository;
    @Autowired private OrderRefundRepository refundRepository;
    @Autowired private OrderReturnRepository returnRepository;
    @Autowired private OrderShipmentRepository shipmentRepository;

    @Test
    void windowBoundaryIsInclusiveAndRejectionIsStableWithoutSideEffects() throws Exception {
        assertThat(policy.window()).isEqualTo(Duration.ofDays(7));
        assertThat(policy.businessZone()).isEqualTo(SEOUL);
        Fixture fixture = paidOrder(3);
        Instant deliveredAt = deliverAll(fixture, Instant.now());
        Instant windowEnd = deliveredAt.plus(policy.window());

        clock.set(windowEnd.minusSeconds(1));
        assertThat(returns.request(returnRequest(fixture, "key-before", 0, 1)).status())
                .isEqualTo(OrderReturnStatus.REQUESTED);
        clock.set(windowEnd);
        assertThat(returns.request(returnRequest(fixture, "key-exact", 0, 1)).status())
                .isEqualTo(OrderReturnStatus.REQUESTED);

        clock.set(windowEnd.plusSeconds(1));
        mockMvc.perform(post("/api/buyers/{buyerRef}/orders/{orderId}/returns", fixture.buyerRef(), fixture.orderId())
                        .header("Idempotency-Key", "key-after")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(fixture.itemIds().get(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_WINDOW_EXPIRED"));
        assertThat(count("SELECT COUNT(*) FROM order_return WHERE order_id = ?", fixture.orderId())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM order_refund_request WHERE order_id = ?", fixture.orderId())).isZero();
    }

    @Test
    void sameKeyReplayAfterExpiryReturnsTheOriginalButANewKeyIsRejected() {
        Fixture fixture = paidOrder(2);
        Instant deliveredAt = deliverAll(fixture, Instant.now());
        clock.set(deliveredAt.plusSeconds(60));
        ReturnResponse.OrderReturn original = returns.request(returnRequest(fixture, "key-replay", 0, 1));

        clock.set(deliveredAt.plus(policy.window()).plusSeconds(1));

        assertThat(returns.request(returnRequest(fixture, "key-replay", 0, 1))).isEqualTo(original);
        assertThatThrownBy(() -> returns.request(returnRequest(fixture, "key-new", 0, 1)))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo(OrderException.Code.RETURN_WINDOW_EXPIRED));
    }

    @Test
    void splitShipmentsAreJudgedPerShipmentAndAMixedRequestIsAllOrNothing() {
        Fixture fixture = paidOrder(1, 1);
        Instant start = Instant.now();
        clock.set(start);
        fulfillments.start(fixture.sellerId(), fixture.orderId());
        UUID shipmentA = createShipment(fixture, 0);
        UUID shipmentB = createShipment(fixture, 1);
        clock.set(start.plusSeconds(10));
        shipments.deliver(fixture.sellerId(), fixture.orderId(), shipmentA);
        Instant deliveredA = shipmentDeliveredAt(shipmentA);
        // 주문 전체가 배송 완료되기 전에는 기존 규칙대로 어떤 줄도 반품할 수 없다.
        assertThatThrownBy(() -> returns.request(returnRequest(fixture, "key-early", 0, 1)))
                .isInstanceOf(OrderException.class);
        clock.set(deliveredA.plus(Duration.ofDays(5)));
        shipments.deliver(fixture.sellerId(), fixture.orderId(), shipmentB);
        Instant deliveredB = shipmentDeliveredAt(shipmentB);

        // 출고 A는 7일이 지났고 B는 아직 안 지났다. 주문 단위 기준이면 두 줄이 같은 결과여야 하므로 구분된다.
        clock.set(deliveredA.plus(policy.window()).plusSeconds(1));
        assertThat(deliveredB.isAfter(deliveredA)).isTrue();
        assertThatThrownBy(() -> returns.request(returnRequest(fixture, "key-a", 0, 1)))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo(OrderException.Code.RETURN_WINDOW_EXPIRED));
        assertThatThrownBy(() -> returns.request(new OrderRequest.Return(fixture.buyerRef(), fixture.orderId(),
                "key-mixed", List.of(new OrderRequest.Return.Item(fixture.itemIds().get(0), 1),
                        new OrderRequest.Return.Item(fixture.itemIds().get(1), 1)))))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo(OrderException.Code.RETURN_WINDOW_EXPIRED));
        assertThat(count("SELECT COUNT(*) FROM order_return WHERE order_id = ?", fixture.orderId())).isZero();
        assertThat(returns.request(returnRequest(fixture, "key-b", 1, 1)).status())
                .isEqualTo(OrderReturnStatus.REQUESTED);
    }

    @Test
    void requestWaitingForTheOrderLockIsJudgedAtTheClockAfterTheLockIsAcquired() throws Exception {
        Fixture fixture = paidOrder(1);
        Instant deliveredAt = deliverAll(fixture, Instant.now());
        Instant windowEnd = deliveredAt.plus(policy.window());
        clock.set(windowEnd);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection(); Connection observer = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT order_id FROM purchase_order WHERE order_id = ? FOR UPDATE")) {
                lock.setObject(1, fixture.orderId());
                lock.executeQuery().close();
            }
            Future<ReturnResponse.OrderReturn> waiting = executor.submit(
                    () -> returns.request(returnRequest(fixture, "key-waiting", 0, 1)));
            PostgresLockWaitProbe.awaitWaiter(observer, PostgresLockWaitProbe.backendPid(holder),
                    PostgresLockWaitProbe.backendPid(observer), Duration.ofSeconds(20), Duration.ofMillis(50));
            // 요청은 경계 시각에 들어왔지만 잠금을 기다리는 사이 시계가 경계를 넘는다.
            clock.set(windowEnd.plusSeconds(1));
            holder.commit();

            assertThatThrownBy(() -> waiting.get(20, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(OrderException.class)
                    .rootCause().satisfies(error -> assertThat(((OrderException) error).code())
                            .isEqualTo(OrderException.Code.RETURN_WINDOW_EXPIRED));
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM order_return WHERE order_id = ?", fixture.orderId())).isZero();
    }

    @Test
    void overdueInspectionIsOnlyFlaggedAndALateInspectionStillRefundsTheSameAmount() {
        Fixture fixture = paidOrder(3);
        Instant monday = nextMondayTenKst(Instant.now());
        assertThat(monday.atZone(SEOUL).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        deliverAll(fixture, monday);
        ReturnResponse.OrderReturn first = returns.request(returnRequest(fixture, "key-1", 0, 1));
        ReturnResponse.OrderReturn second = returns.request(returnRequest(fixture, "key-2", 0, 1));
        returns.receive(fixture.sellerId(), fixture.orderId(), first.returnId());
        returns.receive(fixture.sellerId(), fixture.orderId(), second.returnId());
        clock.set(monday.plus(Duration.ofDays(3)));
        ReturnResponse.OrderReturn third = returns.request(returnRequest(fixture, "key-3", 0, 1));
        returns.receive(fixture.sellerId(), fixture.orderId(), third.returnId());

        // 월요일 수령 + 5영업일 = 다음 월요일, 마감은 그 다음 날 00:00(서울)이다.
        Instant firstDue = monday.atZone(SEOUL).toLocalDate().plusDays(8).atStartOfDay(SEOUL).toInstant();
        clock.set(firstDue.minusSeconds(1));
        assertThat(sellerView(fixture, first.returnId()).inspectionDueAt()).isEqualTo(firstDue);
        assertThat(sellerView(fixture, first.returnId()).inspectionOverdue()).isFalse();
        inspectionMetrics.refresh();
        assertThat(overdueGauge()).isZero();

        clock.set(firstDue);
        inspectionMetrics.refresh();
        assertThat(sellerView(fixture, first.returnId()).inspectionOverdue()).isTrue();
        assertThat(sellerView(fixture, second.returnId()).inspectionOverdue()).isTrue();
        assertThat(sellerView(fixture, third.returnId()).inspectionOverdue()).isFalse();
        assertThat(overdueGauge()).isEqualTo(2.0);
        // 표시일 뿐이다: 시간이 지나도 상태와 환불은 바뀌지 않고, 수령 확인을 다시 불러도 수령 시각은 그대로다.
        assertThat(count("SELECT COUNT(*) FROM order_return WHERE order_id = ? AND return_status = 'RECEIVED'",
                fixture.orderId())).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM order_refund_request WHERE order_id = ?", fixture.orderId())).isZero();
        assertThat(returns.receive(fixture.sellerId(), fixture.orderId(), first.returnId()).receivedAt())
                .isEqualTo(sellerView(fixture, first.returnId()).receivedAt());
        assertThat(sellerView(fixture, first.returnId()).inspectionDueAt()).isEqualTo(firstDue);

        // 늦은 검수도 막지 않고 제때 검수한 건과 같은 금액을 환불한다.
        ReturnResponse.OrderReturn late = returns.inspect(fixture.sellerId(), fixture.orderId(), first.returnId(),
                inspection(fixture, "PASSED"));
        ReturnResponse.OrderReturn onTime = returns.inspect(fixture.sellerId(), fixture.orderId(), third.returnId(),
                inspection(fixture, "PASSED"));
        assertThat(late.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(late.items().get(0).refundAmountWon()).isEqualTo(onTime.items().get(0).refundAmountWon());
        assertThat(count("SELECT COUNT(*) FROM order_refund_request WHERE order_id = ?", fixture.orderId()))
                .isEqualTo(2);
        assertThat(sellerView(fixture, first.returnId()).inspectionOverdue()).isFalse();
        inspectionMetrics.refresh();
        assertThat(overdueGauge()).isEqualTo(1.0);
    }

    @Test
    void disablingWindowEnforcementRestoresUnboundedReturnsAndEnablingRestoresTheRejection() {
        Fixture fixture = paidOrder(2);
        Instant deliveredAt = deliverAll(fixture, Instant.now());
        clock.set(deliveredAt.plus(Duration.ofDays(400)));

        OrderReturnService disabled = serviceWith(new ReturnPolicy(
                policy.window(), false, policy.inspectionBusinessDays(), policy.businessZone()));
        OrderReturnService enabled = serviceWith(policy);

        assertThat(disabled.request(returnRequest(fixture, "key-disabled", 0, 1)).status())
                .isEqualTo(OrderReturnStatus.REQUESTED);
        assertThatThrownBy(() -> enabled.request(returnRequest(fixture, "key-enabled", 0, 1)))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo(OrderException.Code.RETURN_WINDOW_EXPIRED));
    }

    private OrderReturnService serviceWith(ReturnPolicy returnPolicy) {
        return new OrderReturnServiceImpl(transactionManager, orderRepository, itemRepository, fulfillmentRepository,
                refundRepository, returnRepository, shipmentRepository, refunds, clock, returnPolicy);
    }

    private ReturnResponse.SellerOrderReturn sellerView(Fixture fixture, UUID returnId) {
        return returns.findForSeller(fixture.sellerId(), null, 100, null).returns().stream()
                .filter(row -> row.returnId().equals(returnId)).findFirst().orElseThrow();
    }

    private double overdueGauge() {
        return meters.get("order.return.inspection_overdue_count").gauge().value();
    }

    private OrderRequest.ReturnInspection inspection(Fixture fixture, String result) {
        return new OrderRequest.ReturnInspection(List.of(new OrderRequest.ReturnInspection.Item(
                fixture.itemIds().get(0), OrderRequest.ReturnInspection.Result.valueOf(result))));
    }

    private static OrderRequest.Return returnRequest(Fixture fixture, String key, int itemIndex, int quantity) {
        return new OrderRequest.Return(fixture.buyerRef(), fixture.orderId(), key,
                List.of(new OrderRequest.Return.Item(fixture.itemIds().get(itemIndex), quantity)));
    }

    /** {@code from} 이후 첫 월요일 10:00(서울). 시험이 시계를 다음 영업일 시나리오로 옮길 때 쓴다. */
    private static Instant nextMondayTenKst(Instant from) {
        ZonedDateTime day = from.atZone(SEOUL).plusDays(1).withHour(10).truncatedTo(ChronoUnit.HOURS);
        while (day.getDayOfWeek() != DayOfWeek.MONDAY) {
            day = day.plusDays(1);
        }
        return day.toInstant();
    }

    private int count(String sql, UUID orderId) {
        return jdbc.queryForObject(sql, Integer.class, orderId);
    }

    private Instant shipmentDeliveredAt(UUID shipmentId) {
        return jdbc.queryForObject("SELECT delivered_at FROM order_shipment WHERE shipment_id = ?",
                Instant.class, shipmentId);
    }

    private UUID createShipment(Fixture fixture, int itemIndex) {
        return shipments.create(new OrderRequest.Shipment(fixture.sellerId(), fixture.orderId(),
                "return-window-shipment-" + UUID.randomUUID(), null, null,
                List.of(new OrderRequest.Shipment.Item(fixture.itemIds().get(itemIndex), fixture.quantities().get(itemIndex)))))
                .shipmentId();
    }

    /** 모든 품목을 한 출고로 보내고 {@code at}에 배송 완료 처리한다. DB에 저장된 delivered_at을 돌려준다. */
    private Instant deliverAll(Fixture fixture, Instant at) {
        clock.set(at);
        fulfillments.start(fixture.sellerId(), fixture.orderId());
        List<OrderRequest.Shipment.Item> lines = new ArrayList<>();
        for (int index = 0; index < fixture.itemIds().size(); index++) {
            lines.add(new OrderRequest.Shipment.Item(fixture.itemIds().get(index), fixture.quantities().get(index)));
        }
        UUID shipmentId = shipments.create(new OrderRequest.Shipment(fixture.sellerId(), fixture.orderId(),
                "return-window-shipment-" + UUID.randomUUID(), null, null, lines)).shipmentId();
        shipments.deliver(fixture.sellerId(), fixture.orderId(), shipmentId);
        return shipmentDeliveredAt(shipmentId);
    }

    /** 품목마다 SKU 하나를 만들고(재고 = 수량) 결제까지 끝낸 주문을 만든다. */
    private Fixture paidOrder(int... quantities) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Return window product", null));
        String buyerRef = "return-window-buyer-" + UUID.randomUUID();
        List<String> skuIds = new ArrayList<>();
        for (int quantity : quantities) {
            String skuId = "return-window-" + UUID.randomUUID();
            products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, 125, quantity));
            carts.setItemQuantity(sellerId, buyerRef, skuId, quantity);
            skuIds.add(skuId);
        }
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "return-window-checkout-" + UUID.randomUUID());
        UUID orderId = checkout.orders().get(0).orderId();
        List<UUID> itemIds = new ArrayList<>();
        for (String skuId : skuIds) {
            itemIds.add(jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                    UUID.class, orderId, skuId));
        }
        CheckoutResponse pending = checkoutPayments.submit(checkout.checkoutId());
        OrderViewResponse.Order paid = checkoutPayments
                .completeApproval(checkout.checkoutId(), approvalToken(pending.paymentApprovalUrl()))
                .orders().get(0);
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        List<Integer> quantityList = new ArrayList<>();
        for (int quantity : quantities) quantityList.add(quantity);
        return new Fixture(sellerId, paid.orderId(), buyerRef, itemIds, quantityList);
    }

    private record Fixture(UUID sellerId, UUID orderId, String buyerRef, List<UUID> itemIds, List<Integer> quantities) {}

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

        void set(Instant instant) { current.set(instant); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
