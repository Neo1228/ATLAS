package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.fake.FakePsp;
import com.atlas.payment.service.PaymentService;
import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.service.SettlementService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class SettlementLedgerIntegrationTest {
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
    @Autowired private FakePsp fakePsp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService payments;
    @Autowired private SettlementService settlement;

    @Test
    void confirmedCapturePostsBalancedGrossEntriesAndReplayIsIdempotent() {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = registerSku(sellerId, 125, 5);
        String buyerRef = "settlement-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 2);
        OrderResponse checkout = checkouts.checkout(sellerId, buyerRef, "settlement-" + UUID.randomUUID());

        OrderResponse paid = orders.submitPayment(checkout.orderId());

        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        assertThat(paid.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(journalCount(paid.paymentAttemptId())).isEqualTo(1);
        assertThat(entries(paid.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));

        OrderResponse replayed = orders.reconcilePayment(paid.orderId());

        assertThat(replayed.state()).isEqualTo(OrderState.PAID);
        assertThat(journalCount(paid.paymentAttemptId())).isEqualTo(1);
        assertThat(entries(paid.paymentAttemptId())).hasSize(2);
    }

    @Test
    void declinedPaymentDoesNotCreateSettlementJournal() {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = registerSku(sellerId, 300, 2);
        String buyerRef = "declined-settlement-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        OrderResponse checkout = checkouts.checkout(sellerId, buyerRef, "declined-settlement-" + UUID.randomUUID());
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.DECLINED);

        OrderResponse declined = orders.submitPayment(checkout.orderId());

        assertThat(declined.paymentState()).isEqualTo(PaymentState.DECLINED);
        assertThat(declined.paymentAttemptId()).isNotNull();
        assertThat(journalCount(declined.paymentAttemptId())).isZero();
    }

    @Test
    void failedSecondEntryRollsBackOrderAndLedgerThenReconciliationRetries() {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = registerSku(sellerId, 350, 2);
        String buyerRef = "settlement-retry-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        OrderResponse checkout = checkouts.checkout(sellerId, buyerRef, "settlement-retry-" + UUID.randomUUID());
        fakePsp.enqueue(PaymentPhase.CAPTURE, FakePsp.Result.PENDING, FakePsp.Result.CAPTURED);

        OrderResponse pending = orders.submitPayment(checkout.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.CAPTURE_PENDING);
        var captured = payments.reconcile(pending.orderId());
        assertThat(captured.state()).isEqualTo(PaymentState.CAPTURED);

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_seller_payable_entry() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.account_code = 'SELLER_PAYABLE' THEN
                        RAISE EXCEPTION 'simulated settlement entry failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_seller_payable_entry
                BEFORE INSERT ON settlement_entry
                FOR EACH ROW EXECUTE FUNCTION fail_seller_payable_entry()
                """);
        try {
            assertThatThrownBy(() -> orders.reconcilePayment(pending.orderId())).isInstanceOf(RuntimeException.class);
            assertThat(journalCount(captured.attemptId())).isZero();
            OrderResponse unchanged = orders.find(pending.orderId());
            assertThat(unchanged.state()).isEqualTo(OrderState.PAYMENT_PENDING);
            assertThat(unchanged.paymentState()).isEqualTo(PaymentState.CAPTURED);
            assertThat(unchanged.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_seller_payable_entry ON settlement_entry");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_seller_payable_entry()");
        }

        OrderResponse recovered = orders.reconcilePayment(pending.orderId());
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(journalCount(captured.attemptId())).isEqualTo(1);
        assertThat(entries(captured.attemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 350),
                new Entry("SELLER_PAYABLE", "CREDIT", 350));
    }

    @Test
    void concurrentReplayCreatesOnlyOneBalancedJournal() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        CapturePostingRequest request = new CapturePostingRequest(orderId, attemptId, sellerId, 4_500);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                settlement.recordCapture(request);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                settlement.recordCapture(request);
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(journalCount(attemptId)).isEqualTo(1);
        assertThat(entries(attemptId)).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 4_500),
                new Entry("SELLER_PAYABLE", "CREDIT", 4_500));
    }

    private String registerSku(UUID sellerId, long priceWon, int initialStock) {
        var product = products.createProduct(sellerId, new CreateProductRequest("Ledger test product", null));
        String skuId = "ledger-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, priceWon, initialStock));
        return skuId;
    }

    private int journalCount(UUID attemptId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, attemptId);
    }

    private List<Entry> entries(UUID attemptId) {
        return jdbc.query("""
                SELECT entry.account_code, entry.entry_side, entry.amount_won
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.source_attempt_id = ?
                ORDER BY entry.account_code
                """, (resultSet, rowNum) -> new Entry(
                resultSet.getString("account_code"), resultSet.getString("entry_side"),
                resultSet.getLong("amount_won")), attemptId);
    }

    private record Entry(String accountCode, String side, long amountWon) { }
}
