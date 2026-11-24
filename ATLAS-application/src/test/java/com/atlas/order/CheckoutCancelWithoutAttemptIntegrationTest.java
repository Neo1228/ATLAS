package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PSP 에 보낸 작업이 없는 상태에서 취소가 끝까지 닫히는지 검증한다.
 *
 * <p>결함: 결제 시도가 없거나 NOT_STARTED 일 때 cancel 은 checkout 을 CANCEL_PENDING 으로만
 * 바꾸고 반환했다. 만료 sweep 은 RESERVED 만 다루므로 그 checkout 은 영원히 CANCEL_PENDING 에
 * 남았고 재고 예약도 묶였다. 이어서 문서화된 복구 호출인 reconcile 을 부르면, 결제 계층은
 * NOT_STARTED 승인 단계를 실행 가능하다고 보고 구매자가 이미 취소한 checkout 에 새 PayPal 주문을
 * 만들었다.
 */
@Testcontainers
@SpringBootTest
class CheckoutCancelWithoutAttemptIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService cartCheckouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private PaymentService payments;
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 결제 시도를 NOT_STARTED 로 준비한 뒤 checkout 이 결제 대기로 넘어간 순간(submit 이 provider 를
     * 부르기 직전) 취소가 들어온 상황을 만든다. 취소는 예약을 풀고 CANCELLED 로 닫아야 한다.
     */
    @Test
    void cancelWithNotStartedAttemptClosesCheckoutAndReleasesStock() {
        Fixture fixture = checkoutAwaitingProviderCall();

        CheckoutResponse cancelled = checkoutPayments.cancel(fixture.checkoutId(), "cancel-" + UUID.randomUUID());

        assertThat(cancelled.state()).isEqualTo(BuyerCheckoutState.CANCELLED);
        assertClosedAndReleased(fixture);
        assertNoProviderOrderCreated(fixture.checkoutId());
        assertThat(attemptState(fixture.checkoutId()))
                .as("펜스: 시도가 종결돼야 이후 submit 이 provider 를 부르지 않는다").isEqualTo("VOIDED");
    }

    /**
     * 펜스는 provider 단계가 이미 시작된 시도를 종결하지 않는다.
     *
     * <p>이 경우 취소는 예약을 풀면 안 된다. 진행 중인 승인·캡처 결과로 수렴해야 하기 때문이다.
     */
    @Test
    void fenceRefusesAttemptThatAlreadyReachedProvider() {
        Fixture fixture = checkoutAwaitingProviderCall();
        jdbc.update("UPDATE payment_attempt SET state = 'AUTHORIZATION_PENDING' WHERE checkout_id = ?",
                fixture.checkoutId());

        assertThat(payments.abandonUnstartedCheckout(fixture.checkoutId())).isFalse();
        assertThat(attemptState(fixture.checkoutId())).isEqualTo("AUTHORIZATION_PENDING");
        assertThat(inventory.find(fixture.sellerId(), fixture.skuId()).reserved())
                .as("provider 단계가 시작됐으면 예약은 유지된다").isEqualTo(1);
    }

    private String attemptState(UUID checkoutId) {
        return jdbc.queryForObject("SELECT state FROM payment_attempt WHERE checkout_id = ?",
                String.class, checkoutId);
    }

    /**
     * 이전 코드가 남긴 정체 상태(CANCEL_PENDING + NOT_STARTED)에서 reconcile 을 부른다.
     * 결제 계층을 건드리지 않고 CANCELLED 로 수렴해야 하며, 새 PayPal 주문이 생기면 안 된다.
     */
    @Test
    void reconcileOfCancelPendingCheckoutWithoutProviderWorkDoesNotCreateProviderOrder() {
        Fixture fixture = checkoutAwaitingProviderCall();
        jdbc.update("UPDATE buyer_checkout SET state = 'CANCEL_PENDING', cancellation_requested_at = now() WHERE checkout_id = ?",
                fixture.checkoutId());
        jdbc.update("UPDATE purchase_order SET state = 'CANCEL_PENDING' WHERE checkout_id = ?",
                fixture.checkoutId());

        CheckoutResponse reconciled = checkoutPayments.reconcile(fixture.checkoutId(),
                "reconcile-" + UUID.randomUUID());

        assertThat(reconciled.state()).isEqualTo(BuyerCheckoutState.CANCELLED);
        assertClosedAndReleased(fixture);
        assertNoProviderOrderCreated(fixture.checkoutId());

        // 재시도는 멱등이다.
        CheckoutResponse again = checkoutPayments.reconcile(fixture.checkoutId(), "reconcile-" + UUID.randomUUID());
        assertThat(again.state()).isEqualTo(BuyerCheckoutState.CANCELLED);
        assertThat(inventory.find(fixture.sellerId(), fixture.skuId()).available()).isEqualTo(INITIAL_STOCK);
    }

    private static final int INITIAL_STOCK = 3;

    private Fixture checkoutAwaitingProviderCall() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new CreateProductRequest("cancel-no-attempt-" + UUID.randomUUID(), null));
        String skuId = "cancel-no-attempt-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 1000, INITIAL_STOCK));
        String buyerRef = "cancel-no-attempt-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse checkout = cartCheckouts.checkout(buyerRef, "cancel-no-attempt-" + UUID.randomUUID());
        UUID checkoutId = checkout.checkoutId();

        // submit 이 잠금 transaction 에서 하는 일(PAYMENT_PENDING + NOT_STARTED attempt 준비)을 재현한다.
        // provider 호출은 하지 않는다 — 그 직전에 취소가 들어온 상황이다.
        payments.prepareCheckout(checkoutId, BigDecimal.valueOf(checkout.totalWon()));
        jdbc.update("UPDATE buyer_checkout SET state = 'PAYMENT_PENDING' WHERE checkout_id = ?", checkoutId);
        jdbc.update("UPDATE purchase_order SET state = 'PAYMENT_SUBMITTED' WHERE checkout_id = ?", checkoutId);

        assertThat(inventory.find(sellerId, skuId).reserved()).isEqualTo(1);
        return new Fixture(checkoutId, sellerId, skuId);
    }

    private void assertClosedAndReleased(Fixture fixture) {
        List<String> childStates = jdbc.queryForList(
                "SELECT state FROM purchase_order WHERE checkout_id = ?", String.class, fixture.checkoutId());
        assertThat(childStates).isNotEmpty().allMatch("CANCELLED"::equals);
        InventoryPoolResponse pool = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(pool.reserved()).as("취소된 checkout 의 재고 예약은 풀려야 한다").isZero();
        assertThat(pool.available()).isEqualTo(INITIAL_STOCK);
    }

    private void assertNoProviderOrderCreated(UUID checkoutId) {
        Integer providerOrders = jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempt WHERE checkout_id = ? AND provider_order_id IS NOT NULL",
                Integer.class, checkoutId);
        assertThat(providerOrders).as("취소된 checkout 에 PayPal 주문이 만들어지면 안 된다").isZero();
    }

    private record Fixture(UUID checkoutId, UUID sellerId, String skuId) { }
}
