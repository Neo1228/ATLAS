package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderRefundStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
    @Autowired private OrderService orders;
    @Autowired private OrderFulfillmentService fulfillments;
    @Autowired private OrderRefundService refunds;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @Test
    void partialAndFullRefundsReplayAndRestoreOnlyRequestedUnitsWithoutChangingPaidState() {
        Fixture fixture = paidOrder(2, 5, 125);
        UUID itemId = fixture.orderItemId();
        OrderRefundRequest oneUnit = request(itemId, 1);

        OrderRefundResponse partial = refunds.refund(fixture.sellerId(), fixture.orderId(), "refund-partial-key", oneUnit);

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

        assertThat(refunds.refund(fixture.sellerId(), fixture.orderId(), "refund-partial-key", oneUnit))
                .isEqualTo(partial);
        assertThatThrownBy(() -> refunds.refund(fixture.sellerId(), fixture.orderId(), "refund-partial-key",
                request(itemId, 2))).isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThatThrownBy(() -> refunds.refund(fixture.sellerId(), fixture.orderId(), "refund-over-limit-key",
                request(itemId, 2))).isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(fixture, 4, 1);
        assertThat(journalCountForRefund(partial.refundId())).isEqualTo(1);

        fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(), FulfillmentStatus.IN_PROGRESS);
        OrderRefundResponse full = refunds.refund(fixture.sellerId(), fixture.orderId(), "refund-final-key",
                request(itemId, 1));

        assertThat(full.recordedAmountWon()).isEqualTo(125);
        assertThat(full.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));
        assertThatThrownBy(() -> fulfillments.updateStatus(fixture.sellerId(), fixture.orderId(), FulfillmentStatus.SHIPPED))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(journalCountForRefund(full.refundId())).isEqualTo(1);
    }

    @Test
    void rejectsUnpaidOrdersOverRefundsAndRefundsAfterShipmentWithoutChangingStock() {
        Fixture unpaid = unpaidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(unpaid.sellerId(), unpaid.orderId(), "unpaid-refund-key",
                request(unpaid.orderItemId(), 1))).isInstanceOf(OrderException.class);
        assertThat(refunds.find(unpaid.sellerId(), unpaid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(unpaid, 4, 1, 0);

        Fixture paid = paidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(paid.sellerId(), paid.orderId(), "over-refund-key",
                request(paid.orderItemId(), 2))).isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();

        fulfillments.updateStatus(paid.sellerId(), paid.orderId(), FulfillmentStatus.IN_PROGRESS);
        fulfillments.updateStatus(paid.sellerId(), paid.orderId(), FulfillmentStatus.SHIPPED);
        assertThatThrownBy(() -> refunds.refund(paid.sellerId(), paid.orderId(), "shipped-refund-key",
                request(paid.orderItemId(), 1))).isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(refunds.find(paid.sellerId(), paid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();
    }

    @Test
    void concurrentRetryCreatesOneRefundAndRestoresStockOnce() throws Exception {
        Fixture fixture = paidOrder(1, 5, 300);
        OrderRefundRequest request = request(fixture.orderItemId(), 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OrderRefundResponse> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(fixture.sellerId(), fixture.orderId(), "concurrent-refund-key", request);
            });
            Future<OrderRefundResponse> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(fixture.sellerId(), fixture.orderId(), "concurrent-refund-key", request);
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
        OrderRefundRequest request = request(fixture.orderItemId(), 1);
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
            assertThatThrownBy(() -> refunds.refund(fixture.sellerId(), fixture.orderId(), "rollback-refund-key", request))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_refund_settlement_entry_trigger ON settlement_entry");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_refund_settlement_entry()");
        }

        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(fixture, 4, 1);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();

        OrderRefundResponse recovered = refunds.refund(fixture.sellerId(), fixture.orderId(), "rollback-refund-key", request);
        assertThat(recovered.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(journalCountForRefund(recovered.refundId())).isEqualTo(1);
        assertThat(entriesForRefund(recovered.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 450),
                new Entry("SELLER_PAYABLE", "DEBIT", 450));
    }

    private Fixture paidOrder(int quantity, int initialStock, long unitPriceWon) {
        Fixture fixture = createOrder(quantity, initialStock, unitPriceWon);
        OrderResponse paid = orders.submitPayment(fixture.orderId());
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        return new Fixture(fixture.sellerId(), fixture.orderId(), fixture.orderItemId(), fixture.skuId(),
                paid.paymentAttemptId());
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
        OrderResponse order = checkouts.checkout(sellerId, buyerRef, "refund-checkout-" + UUID.randomUUID());
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, order.orderId());
        return new Fixture(sellerId, order.orderId(), orderItemId, skuId, null);
    }

    private static OrderRefundRequest request(UUID orderItemId, int quantity) {
        return new OrderRefundRequest(List.of(new OrderRefundRequest.Item(orderItemId, quantity)));
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

    private record Fixture(UUID sellerId, UUID orderId, UUID orderItemId, String skuId, UUID paymentAttemptId) {
    }

    private record Entry(String accountCode, String side, long recordedAmountWon) {
    }
}
