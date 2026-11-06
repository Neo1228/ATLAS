package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.persistence.state.OrderReturnStatus;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.order.dto.request.OrderReturnInspectionRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.service.OrderReturnService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class OrderRefundLifecycleIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

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
    @Autowired private OrderService orders;
    @Autowired private OrderFulfillmentService fulfillments;
    @Autowired private OrderRefundService refunds;
    @Autowired private OrderReturnService returns;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void returnRequestIsDeliveryGatedAndReceiptDoesNotRefundOrRestock() {
        Fixture fixture = paidOrder(1, 2, 125);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnRequest request = returnRequest(buyerRef, fixture.orderId(), "return-delivery-key",
                new OrderReturnRequest.Item(fixture.orderItemId(), 1));
        assertThatThrownBy(() -> returns.request(request)).isInstanceOf(OrderException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_return WHERE order_id = ?",
                Integer.class, fixture.orderId())).isZero();
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertStock(fixture, 1, 1);
        deliver(fixture);
        OrderReturnResponse created = returns.request(request);
        assertThatThrownBy(() -> returns.receive(UUID.randomUUID(), fixture.orderId(), created.returnId()))
                .isInstanceOf(OrderException.class);
        assertThat(created.status()).isEqualTo(OrderReturnStatus.REQUESTED);
        OrderReturnResponse received = returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        assertThat(received.status()).isEqualTo(OrderReturnStatus.RECEIVED);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertStock(fixture, 1, 1);
        assertThat(returns.request(request)).isEqualTo(received);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "return-delivery-key",
                new OrderReturnRequest.Item(fixture.orderItemId(), 2)))).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> returns.find("not-the-buyer", fixture.orderId(), created.returnId()))
                .isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> returns.find(buyerRef, fixture.orderId(), UUID.randomUUID()))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.NOT_FOUND));
    }
    @Test
    void returnRequestReceiptAndInspectionAreAvailableThroughHttpRoutes() throws Exception {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        mockMvc.perform(post("/api/buyers/{buyerRef}/orders/{orderId}/returns", buyerRef, fixture.orderId())
                        .header("Idempotency-Key", "http-null-return-item")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[null]}
                                """))
                .andExpect(status().isBadRequest());
        String response = mockMvc.perform(post("/api/buyers/{buyerRef}/orders/{orderId}/returns",
                        buyerRef, fixture.orderId())
                        .header("Idempotency-Key", "http-return-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        UUID returnId = objectMapper.readValue(response, OrderReturnResponse.class).returnId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/receipt",
                        fixture.sellerId(), fixture.orderId(), returnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection",
                        fixture.sellerId(), fixture.orderId(), returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[null]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection",
                        fixture.sellerId(), fixture.orderId(), returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","result":"PASSED"}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_RECORDED"))
                .andExpect(jsonPath("$.items[0].refundId").exists());
    }

    @Test
    void mixedInspectionRefundsOnlyPassedLineAndFailedQuantityRemainsUnavailable() {
        MultiItemFixture fixture = paidOrderWithTwoItems();
        deliver(fixture.sellerId(), fixture.orderId());
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnResponse created = returns.request(new OrderReturnRequest(buyerRef, fixture.orderId(),
                "return-mixed-key", List.of(new OrderReturnRequest.Item(fixture.firstItemId(), 1),
                new OrderReturnRequest.Item(fixture.secondItemId(), 1))));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderReturnResponse inspected = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(),
                new OrderReturnInspectionRequest(List.of(
                        new OrderReturnInspectionRequest.Item(fixture.firstItemId(), OrderReturnInspectionRequest.Result.PASSED),
                        new OrderReturnInspectionRequest.Item(fixture.secondItemId(), OrderReturnInspectionRequest.Result.FAILED))));
        assertThat(inspected.status()).isEqualTo(OrderReturnStatus.PARTIALLY_RECORDED);
        assertThat(inspected.items()).filteredOn(item -> item.orderItemId().equals(fixture.firstItemId()))
                .singleElement().satisfies(item -> {
                    assertThat(item.refundId()).isNotNull();
                    assertThat(item.refundAmountWon()).isEqualTo(100L);
                });
        assertThat(inspected.items()).filteredOn(item -> item.orderItemId().equals(fixture.secondItemId()))
                .singleElement().satisfies(item -> assertThat(item.refundId()).isNull());
        assertThat(inventory.find(fixture.sellerId(), fixture.firstSkuId()).available()).isEqualTo(1);
        assertThat(inventory.find(fixture.sellerId(), fixture.firstSkuId()).claimed()).isZero();
        assertThat(inventory.find(fixture.sellerId(), fixture.secondSkuId()).claimed()).isEqualTo(1);
        assertThat(returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(),
                new OrderReturnInspectionRequest(List.of(
                        new OrderReturnInspectionRequest.Item(fixture.firstItemId(), OrderReturnInspectionRequest.Result.PASSED),
                        new OrderReturnInspectionRequest.Item(fixture.secondItemId(), OrderReturnInspectionRequest.Result.FAILED)))))
                .isEqualTo(inspected);
        assertThatThrownBy(() -> returns.request(new OrderReturnRequest(buyerRef, fixture.orderId(),
                "failed-line-retry", List.of(new OrderReturnRequest.Item(fixture.secondItemId(), 1)))))
                .isInstanceOf(OrderException.class);
    }
    @Test
    void returnQuantityLimitIncludesEarlierRefundsAndPassedReturnsAcrossCases() {
        Fixture fixture = paidOrder(3, 3, 100);
        refunds.refund(request(fixture.sellerId(), fixture.orderId(), "before-return-refund",
                fixture.orderItemId(), 1));
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnResponse first = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-two-units",
                new OrderReturnRequest.Item(fixture.orderItemId(), 2)));
        returns.receive(fixture.sellerId(), fixture.orderId(), first.returnId());
        OrderReturnResponse passed = returns.inspect(fixture.sellerId(), fixture.orderId(), first.returnId(),
                new OrderReturnInspectionRequest(List.of(new OrderReturnInspectionRequest.Item(
                        fixture.orderItemId(), OrderReturnInspectionRequest.Result.PASSED))));
        assertThat(passed.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "return-over-total",
                new OrderReturnRequest.Item(fixture.orderItemId(), 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
    }
    @Test
    void allFailedInspectionKeepsClaimedStockAndCannotBeRepeatedOrRequestedAgain() {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnResponse created = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-failed-key",
                new OrderReturnRequest.Item(fixture.orderItemId(), 1)));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderReturnInspectionRequest failed = new OrderReturnInspectionRequest(List.of(
                new OrderReturnInspectionRequest.Item(fixture.orderItemId(), OrderReturnInspectionRequest.Result.FAILED)));
        OrderReturnResponse inspected = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), failed);
        assertThat(inspected.status()).isEqualTo(OrderReturnStatus.INSPECTION_FAILED);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertStock(fixture, 0, 1);
        assertThat(returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), failed))
                .isEqualTo(inspected);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "failed-quantity-key",
                new OrderReturnRequest.Item(fixture.orderItemId(), 1)))).isInstanceOf(OrderException.class);
    }
    @Test
    void settlementFailureDuringReturnInspectionRollsBackAndRetrySucceeds() {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnResponse created = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-rollback-key",
                new OrderReturnRequest.Item(fixture.orderItemId(), 1)));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderReturnInspectionRequest passed = new OrderReturnInspectionRequest(List.of(
                new OrderReturnInspectionRequest.Item(fixture.orderItemId(), OrderReturnInspectionRequest.Result.PASSED)));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_return_settlement() RETURNS TRIGGER AS $$
                BEGIN
                  IF NEW.account_code = 'PSP_CLEARING' AND NEW.entry_side = 'CREDIT' THEN
                    RAISE EXCEPTION 'forced settlement failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_return_settlement_trigger
                BEFORE INSERT ON settlement_entry
                FOR EACH ROW EXECUTE FUNCTION fail_return_settlement()
                """);
        assertThatThrownBy(() -> returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), passed))
                .isInstanceOf(RuntimeException.class);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_return_settlement_trigger ON settlement_entry");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_return_settlement()");
        OrderReturnResponse unchanged = returns.find(buyerRef, fixture.orderId(), created.returnId());
        assertThat(unchanged.status()).isEqualTo(OrderReturnStatus.RECEIVED);
        assertThat(unchanged.items().get(0).inspectionResult()).isNull();
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertStock(fixture, 0, 1);
        OrderReturnResponse retried = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), passed);
        assertThat(retried.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
    }
    @Test
    void partialAndFullRefundsReplayAndRestoreOnlyRequestedUnitsWithoutChangingPaidState() {
        Fixture fixture = paidOrder(2, 5, 125);
        UUID itemId = fixture.orderItemId();
        OrderRefundRequest oneUnit = request(fixture.sellerId(), fixture.orderId(), "refund-partial-key",
                itemId, 1);

        OrderRefundResponse partial = refunds.refund(oneUnit);

        assertThat(partial.refundId().version()).isEqualTo(7);
        assertThat(partial.recordedAmountWon()).isEqualTo(125);
        assertThat(partial.status()).isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertStock(fixture, 4, 1);
        assertThat(entriesForRefund(partial.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 125),
                new Entry("SELLER_PAYABLE", "DEBIT", 125));
        assertThat(journalCountForRefund(partial.refundId())).isEqualTo(1);
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));

        assertThat(refunds.refund(oneUnit)).isEqualTo(partial);
        assertThatThrownBy(() -> refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-partial-key", itemId, 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThatThrownBy(() -> refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-over-limit-key", itemId, 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(fixture, 4, 1);
        assertThat(journalCountForRefund(partial.refundId())).isEqualTo(1);

        fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(), FulfillmentStatus.IN_PROGRESS);
        OrderRefundResponse full = refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-final-key", itemId, 1));

        assertThat(full.recordedAmountWon()).isEqualTo(125);
        assertThat(full.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));
        assertThatThrownBy(() -> fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(),
                FulfillmentStatus.SHIPPED))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(journalCountForRefund(full.refundId())).isEqualTo(1);
    }

    @Test
    void refundSummaryAndFulfillmentGateAggregateAcrossOrderItems() {
        MultiItemFixture fixture = paidOrderWithTwoItems();

        OrderRefundResponse firstRefund = refunds.refund(new OrderRefundRequest(
                fixture.sellerId(), fixture.orderId(), "multi-item-first-refund",
                List.of(new OrderRefundRequest.Item(fixture.firstItemId(), 1))));

        assertThat(firstRefund.status()).isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(100);
        assertPool(fixture.sellerId(), fixture.firstSkuId(), 1, 0, 0, 1);
        assertPool(fixture.sellerId(), fixture.secondSkuId(), 0, 0, 1, 1);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(), FulfillmentStatus.IN_PROGRESS)
                .status()).isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(entriesForRefund(firstRefund.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 100),
                new Entry("SELLER_PAYABLE", "DEBIT", 100));

        OrderRefundResponse secondRefund = refunds.refund(new OrderRefundRequest(
                fixture.sellerId(), fixture.orderId(), "multi-item-second-refund",
                List.of(new OrderRefundRequest.Item(fixture.secondItemId(), 1))));

        assertThat(secondRefund.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(350);
        assertPool(fixture.sellerId(), fixture.firstSkuId(), 1, 0, 0, 1);
        assertPool(fixture.sellerId(), fixture.secondSkuId(), 1, 0, 0, 1);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(entriesForRefund(secondRefund.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 250),
                new Entry("SELLER_PAYABLE", "DEBIT", 250));
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 350),
                new Entry("SELLER_PAYABLE", "CREDIT", 350));
        assertThatThrownBy(() -> fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(),
                FulfillmentStatus.SHIPPED))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
    }

    @Test
    void rejectsUnpaidOrdersOverRefundsAndRefundsAfterShipmentWithoutChangingStock() {
        Fixture unpaid = unpaidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(request(unpaid.sellerId(), unpaid.orderId(),
                "unpaid-refund-key", unpaid.orderItemId(), 1)))
                .isInstanceOf(OrderException.class);
        assertThat(refunds.find(unpaid.sellerId(), unpaid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(unpaid, 4, 1, 0);

        Fixture paid = paidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(request(paid.sellerId(), paid.orderId(),
                "over-refund-key", paid.orderItemId(), 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();

        fulfillments.updateStatus(paid.sellerId(), paid.orderId(), FulfillmentStatus.IN_PROGRESS);
        fulfillments.updateStatus(paid.sellerId(), paid.orderId(), FulfillmentStatus.SHIPPED);
        assertThatThrownBy(() -> refunds.refund(request(paid.sellerId(), paid.orderId(),
                "shipped-refund-key", paid.orderItemId(), 1)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(refunds.find(paid.sellerId(), paid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();
    }

    @Test
    void concurrentRetryCreatesOneRefundAndRestoresStockOnce() throws Exception {
        Fixture fixture = paidOrder(1, 5, 300);
        OrderRefundRequest request = request(fixture.sellerId(), fixture.orderId(),
                "concurrent-refund-key", fixture.orderItemId(), 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrderRefundResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(request);
            });
            Future<OrderRefundResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(request);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            OrderRefundResponse one = first.get(10, TimeUnit.SECONDS);
            OrderRefundResponse two = second.get(10, TimeUnit.SECONDS);
            assertThat(one).isEqualTo(two);
            assertStock(fixture, 5, 0);
            assertThat(journalCountForRefund(one.refundId())).isEqualTo(1);
            assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDeliveredReturnRequestsCannotClaimSameUnitsTwice() throws Exception {
        Fixture fixture = paidOrder(2, 2, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnRequest firstRequest = returnRequest(buyerRef, fixture.orderId(), "concurrent-return-one",
                new OrderReturnRequest.Item(fixture.orderItemId(), 2));
        OrderReturnRequest secondRequest = returnRequest(buyerRef, fixture.orderId(), "concurrent-return-two",
                new OrderReturnRequest.Item(fixture.orderItemId(), 2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrderReturnResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.request(firstRequest);
            });
            Future<OrderReturnResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.request(secondRequest);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            OrderReturnResponse accepted = null;
            int conflicts = 0;
            try {
                accepted = first.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(OrderException.class)
                        .satisfies(error -> assertThat(((OrderException) error).code())
                                .isEqualTo(OrderException.Code.CONFLICT));
                conflicts++;
            }
            try {
                accepted = second.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(OrderException.class)
                        .satisfies(error -> assertThat(((OrderException) error).code())
                                .isEqualTo(OrderException.Code.CONFLICT));
                conflicts++;
            }
            assertThat(accepted).isNotNull();
            assertThat(conflicts).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_return WHERE order_id = ?",
                Integer.class, fixture.orderId())).isEqualTo(1);
        long acceptedQuantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(item.quantity), 0)
                FROM order_return_item item
                JOIN order_return return_record ON return_record.return_id = item.return_id
                WHERE return_record.order_id = ?
                """, Long.class, fixture.orderId());
        assertThat(acceptedQuantity).isLessThanOrEqualTo(2L).isEqualTo(2L);
        OrderReturnResponse persisted = returns.find(buyerRef, fixture.orderId(),
                jdbc.queryForObject("SELECT return_id FROM order_return WHERE order_id = ?",
                        UUID.class, fixture.orderId()));
        assertThat(persisted.status()).isEqualTo(OrderReturnStatus.REQUESTED);
        assertThat(persisted.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(fixture.orderItemId());
            assertThat(item.quantity()).isEqualTo(2);
        });
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertPool(fixture.sellerId(), fixture.skuId(), 0, 0, 2, 2);
    }

    @Test
    void concurrentIdenticalInspectionRecordsOneRefundAndRestoresStockOnce() throws Exception {
        Fixture fixture = paidOrder(2, 2, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderReturnResponse requested = returns.request(returnRequest(buyerRef, fixture.orderId(),
                "concurrent-inspection-return", new OrderReturnRequest.Item(fixture.orderItemId(), 2)));
        returns.receive(fixture.sellerId(), fixture.orderId(), requested.returnId());
        OrderReturnInspectionRequest passed = new OrderReturnInspectionRequest(List.of(
                new OrderReturnInspectionRequest.Item(fixture.orderItemId(),
                        OrderReturnInspectionRequest.Result.PASSED)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        OrderReturnResponse firstResult;
        OrderReturnResponse secondResult;
        try {
            Future<OrderReturnResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.inspect(fixture.sellerId(), fixture.orderId(), requested.returnId(), passed);
            });
            Future<OrderReturnResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.inspect(fixture.sellerId(), fixture.orderId(), requested.returnId(), passed);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult = first.get(10, TimeUnit.SECONDS);
            secondResult = second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(firstResult).isEqualTo(secondResult);
        OrderReturnResponse persisted = returns.find(buyerRef, fixture.orderId(), requested.returnId());
        assertThat(persisted.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(persisted.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(fixture.orderItemId());
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.refundId()).isNotNull();
            assertThat(item.refundAmountWon()).isEqualTo(250L);
        });
        UUID refundId = persisted.items().get(0).refundId();
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(250L);
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        long refundedQuantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(item.quantity), 0)
                FROM order_refund_request_item item
                JOIN order_refund_request request ON request.refund_id = item.refund_id
                WHERE request.order_id = ?
                """, Long.class, fixture.orderId());
        assertThat(refundedQuantity).isEqualTo(2L);
        assertThat(journalCountForOrder(fixture.orderId())).isEqualTo(1);
        assertThat(journalCountForRefund(refundId)).isEqualTo(1);
        List<Entry> refundEntries = entriesForRefund(refundId);
        assertThat(refundEntries).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 250),
                new Entry("SELLER_PAYABLE", "DEBIT", 250));
        assertThat(refundEntries.stream().filter(entry -> entry.side().equals("DEBIT"))
                .mapToLong(Entry::recordedAmountWon).sum()).isEqualTo(250L);
        assertThat(refundEntries.stream().filter(entry -> entry.side().equals("CREDIT"))
                .mapToLong(Entry::recordedAmountWon).sum()).isEqualTo(250L);
        assertPool(fixture.sellerId(), fixture.skuId(), 2, 0, 0, 2);
    }

    @Test
    void sellerRefundHttpEndpointsExposeLocalRefundAndAggregateStatus() throws Exception {
        Fixture fixture = paidOrder(1, 3, 200);

        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "http-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedAmountWon").value(200))
                .andExpect(jsonPath("$.executionMode").value("LOCAL_LEDGER_ONLY"))
                .andExpect(jsonPath("$.status").value("FULLY_RECORDED"));

        mockMvc.perform(get("/api/sellers/{sellerId}/orders/{orderId}/refund-record",
                        fixture.sellerId(), fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULLY_RECORDED"))
                .andExpect(jsonPath("$.totalRecordedAmountWon").value(200))
                .andExpect(jsonPath("$.executionMode").value("LOCAL_LEDGER_ONLY"));
    }

    @Test
    void settlementFailureRollsBackRefundStateInventoryAndReversalBeforeRetry() {
        Fixture fixture = paidOrder(1, 5, 450);
        OrderRefundRequest request = request(fixture.sellerId(), fixture.orderId(),
                "rollback-refund-key", fixture.orderItemId(), 1);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_refund_settlement_entry() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.account_code = 'PSP_CLEARING' AND NEW.entry_side = 'CREDIT' THEN
                        RAISE EXCEPTION 'simulated local refund posting failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_refund_settlement_entry_trigger
                BEFORE INSERT ON settlement_entry
                FOR EACH ROW EXECUTE FUNCTION fail_refund_settlement_entry()
                """);
        try {
            assertThatThrownBy(() -> refunds.refund(request))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_refund_settlement_entry_trigger ON settlement_entry");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_refund_settlement_entry()");
        }

        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(fixture, 4, 1);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();

        OrderRefundResponse recovered = refunds.refund(request);
        assertThat(recovered.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(journalCountForRefund(recovered.refundId())).isEqualTo(1);
        assertThat(entriesForRefund(recovered.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 450),
                new Entry("SELLER_PAYABLE", "DEBIT", 450));
    }

    private String buyerRef(UUID orderId) {
        return jdbc.queryForObject("SELECT buyer_ref FROM purchase_order WHERE order_id = ?", String.class, orderId);
    }

    private OrderReturnRequest returnRequest(String buyerRef, UUID orderId, String key, OrderReturnRequest.Item item) {
        return new OrderReturnRequest(buyerRef, orderId, key, List.of(item));
    }

    private void deliver(Fixture fixture) {
        deliver(fixture.sellerId(), fixture.orderId());
    }

    private void deliver(UUID sellerId, UUID orderId) {
        fulfillments.updateStatus(sellerId, orderId, FulfillmentStatus.IN_PROGRESS);
        fulfillments.updateStatus(sellerId, orderId, FulfillmentStatus.SHIPPED);
        fulfillments.updateStatus(sellerId, orderId, FulfillmentStatus.DELIVERED);
    }

    private MultiItemFixture paidOrderWithTwoItems() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Multi-item refund test", null));
        String firstSkuId = "multi-refund-a-" + UUID.randomUUID();
        String secondSkuId = "multi-refund-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSkuId, 100, 1));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSkuId, 250, 1));
        String buyerRef = "multi-refund-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, firstSkuId, 1);
        carts.setItemQuantity(sellerId, buyerRef, secondSkuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "multi-refund-checkout-" + UUID.randomUUID());
        OrderResponse reserved = checkout.orders().get(0);
        UUID firstItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), firstSkuId);
        UUID secondItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), secondSkuId);
        OrderResponse paid = checkoutPayments.submit(checkout.checkoutId()).orders().get(0);
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        return new MultiItemFixture(sellerId, paid.orderId(), firstItemId, firstSkuId, secondItemId, secondSkuId,
                paid.paymentAttemptId());
    }

    private void assertPool(UUID sellerId, String skuId, int available, int reserved, int claimed,
            int initialCapacity) {
        InventoryPoolResponse pool = inventory.find(sellerId, skuId);
        assertThat(pool.available()).isEqualTo(available);
        assertThat(pool.reserved()).isEqualTo(reserved);
        assertThat(pool.claimed()).isEqualTo(claimed);
        assertThat(pool.available() + pool.reserved() + pool.claimed()).isEqualTo(initialCapacity);
    }

    private Fixture paidOrder(int quantity, int initialStock, long unitPriceWon) {
        Fixture fixture = createOrder(quantity, initialStock, unitPriceWon);
        OrderResponse paid = checkoutPayments.submit(fixture.checkoutId()).orders().get(0);
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        return new Fixture(fixture.sellerId(), fixture.orderId(), fixture.orderItemId(), fixture.skuId(),
                fixture.checkoutId(), paid.paymentAttemptId());
    }

    private Fixture unpaidOrder(int quantity, int initialStock, long unitPriceWon) {
        return createOrder(quantity, initialStock, unitPriceWon);
    }

    private Fixture createOrder(int quantity, int initialStock, long unitPriceWon) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Refund test product", null));
        String skuId = "refund-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(),
                new RegisterSellerSkuRequest(skuId, unitPriceWon, initialStock));
        String buyerRef = "refund-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, quantity);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "refund-checkout-" + UUID.randomUUID());
        OrderResponse order = checkout.orders().get(0);
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, order.orderId());
        return new Fixture(sellerId, order.orderId(), orderItemId, skuId, checkout.checkoutId(), null);
    }

    private static OrderRefundRequest request(UUID sellerId, UUID orderId, String idempotencyKey,
            UUID orderItemId, int quantity) {
        return new OrderRefundRequest(sellerId, orderId, idempotencyKey,
                List.of(new OrderRefundRequest.Item(orderItemId, quantity)));
    }

    private void assertStock(Fixture fixture, int available, int claimed) {
        InventoryPoolResponse stock = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(stock.available()).isEqualTo(available);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(claimed);
    }

    private void assertStock(Fixture fixture, int available, int reserved, int claimed) {
        InventoryPoolResponse stock = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(stock.available()).isEqualTo(available);
        assertThat(stock.reserved()).isEqualTo(reserved);
        assertThat(stock.claimed()).isEqualTo(claimed);
    }

    private int journalCountForRefund(UUID refundId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_refund_id = ?",
                Integer.class, refundId);
    }

    private int journalCountForOrder(UUID orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_order_id = ? AND source_refund_id IS NOT NULL",
                Integer.class, orderId);
    }

    private int refundRequestCount(UUID orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_request WHERE order_id = ?",
                Integer.class, orderId);
    }

    private List<Entry> entriesForRefund(UUID refundId) {
        return jdbc.query("""
                SELECT entry.account_code, entry.entry_side, entry.amount_won
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.source_refund_id = ?
                ORDER BY entry.account_code
                """, (resultSet, rowNum) -> new Entry(resultSet.getString("account_code"),
                resultSet.getString("entry_side"), resultSet.getLong("amount_won")), refundId);
    }

    private List<Entry> entriesForAttempt(UUID attemptId) {
        return jdbc.query("""
                SELECT entry.account_code, entry.entry_side, entry.amount_won
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.source_attempt_id = ?
                ORDER BY entry.account_code
                """, (resultSet, rowNum) -> new Entry(resultSet.getString("account_code"),
                resultSet.getString("entry_side"), resultSet.getLong("amount_won")), attemptId);
    }

    private record MultiItemFixture(UUID sellerId, UUID orderId, UUID firstItemId, String firstSkuId,
            UUID secondItemId, String secondSkuId, UUID paymentAttemptId) {
    }

    private record Fixture(UUID sellerId, UUID orderId, UUID orderItemId, String skuId, UUID checkoutId, UUID paymentAttemptId) {
    }

    private record Entry(String accountCode, String side, long recordedAmountWon) {
    }
}
