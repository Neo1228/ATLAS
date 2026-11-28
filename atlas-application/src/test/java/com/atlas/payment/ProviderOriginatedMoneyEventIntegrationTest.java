package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.application.service.PayPalWebhookService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
 * PayPal 쪽에서 시작된 금전 사건(대시보드 환불, capture 역전, 분쟁)이 웹훅 경로로 들어왔을 때
 * 판매자 원장에 반영되는지 실제 PostgreSQL(V7 균형·append-only 트리거 포함)로 확인한다.
 */
@Testcontainers
@SpringBootTest
class ProviderOriginatedMoneyEventIntegrationTest extends PayPalIntegrationTestSupport {
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

    private static final PaymentWebhookSignatureRequest SIGNATURE = new PaymentWebhookSignatureRequest(
            "SHA256withRSA", "https://local.test/cert", "tx-provider-event", "signature-local",
            "2026-11-28T00:00:00Z");

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private OrderRefundService refunds;
    @Autowired private PayPalWebhookService webhooks;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void dashboardRefundDebitsSellerOnceAcrossDuplicateDeliveries() {
        Paid paid = paidOrder(10_000);
        long before = sellerHeldWon(paid.sellerId());
        String refundId = "DASH-" + UUID.randomUUID();
        String body = refundEvent("PAYMENT.CAPTURE.REFUNDED", refundId, paid.captureId(), paid.providerAmount());

        webhooks.handle(SIGNATURE, body.replace("@EVENT@", "WH-" + UUID.randomUUID()));
        webhooks.handle(SIGNATURE, body.replace("@EVENT@", "WH-" + UUID.randomUUID()));

        assertThat(providerRefundJournals(paid.orderId())).containsExactly(paid.totalWon());
        assertThat(sellerHeldWon(paid.sellerId())).isEqualTo(before - paid.totalWon());
        assertThat(webhookRetries(refundId)).isZero();
    }

    @Test
    void partialDashboardRefundThenReversalOfRemainderDebitsExactlyTheCapturedWon() {
        Paid paid = paidOrder(9_999);
        long before = sellerHeldWon(paid.sellerId());
        // 약 3분의 1(두 번 반영해도 나머지가 아니다)이면서 원화 환산이 나누어떨어지지 않는 금액을 고른다. 그래야 같은
        // 환불의 이중 반영도, 비율 내림이 남긴 1원 미만 차이를 마지막 건이 메우는지도 합으로 드러난다.
        long captureMinor = paid.providerAmount().movePointRight(2).longValueExact();
        long thirdMinor = captureMinor / 3;
        while (thirdMinor * paid.totalWon() % captureMinor == 0 && thirdMinor < captureMinor / 2) thirdMinor++;
        assertThat(thirdMinor * paid.totalWon() % captureMinor).isNotZero();
        BigDecimal third = BigDecimal.valueOf(thirdMinor, 2);
        String refundId = "DASH-" + UUID.randomUUID();
        String reversalId = "REV-" + UUID.randomUUID();

        String partialRefund = refundEvent("PAYMENT.CAPTURE.REFUNDED", refundId, paid.captureId(), third);
        webhooks.handle(SIGNATURE, partialRefund.replace("@EVENT@", "WH-" + UUID.randomUUID()));
        // 같은 refund 를 다른 event id 로 다시 받는다(PayPal 재전송·PAYMENT.REFUND.COMPLETED 동시 구독). 부분 환불이라
        // capture 남은 금액 검사로는 막히지 않으므로 provider refund id 멱등만이 이중 차감을 막는다.
        webhooks.handle(SIGNATURE, partialRefund.replace("@EVENT@", "WH-" + UUID.randomUUID()));
        webhooks.handle(SIGNATURE, refundEvent("PAYMENT.CAPTURE.REVERSED", reversalId, paid.captureId(),
                paid.providerAmount().subtract(third)).replace("@EVENT@", "WH-" + UUID.randomUUID()));

        List<Long> journals = providerRefundJournals(paid.orderId());
        assertThat(journals).hasSize(2);
        assertThat(journals.stream().mapToLong(Long::longValue).sum()).isEqualTo(paid.totalWon());
        assertThat(sellerHeldWon(paid.sellerId())).isEqualTo(before - paid.totalWon());
    }

    /**
     * 우리 부분 환불, 대시보드 부분 환불, 나머지 역전이 한 capture에 섞여도 원화 합이 capture와 정확히 같고,
     * 그 뒤 우리 환불은 남은 금액을 넘는다고 거절된다. 두 경로 중 하나라도 다른 쪽 합을 빼지 않으면 깨진다.
     */
    @Test
    void ourRefundDashboardRefundAndReversalShareOneRemainingCapture() {
        Paid paid = paidOrder(3_333, 3);
        OrderRequest.Refund oneUnit = new OrderRequest.Refund(paid.sellerId(), paid.orderId(), "atlas-1",
                List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1)));
        assertThat(refunds.refund(oneUnit).paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        long ourMinor = jdbc.queryForObject("SELECT provider_amount_minor FROM payment_refund_operation WHERE attempt_id = ?",
                Long.class, paid.attemptId());
        long captureMinor = paid.providerAmount().movePointRight(2).longValueExact();
        // 역전 원화가 capture 비율 내림으로는 나머지와 맞지 않게 고른다. 그래야 역전이 "남은 전부"로 인식돼 남은 원화를
        // 통째로 받는지(우리 환불까지 뺀 남은 금액을 알아야 한다)가 원화 합으로 드러난다.
        long dashboardMinor = (captureMinor - ourMinor) / 2;
        while ((captureMinor - ourMinor - dashboardMinor) * paid.totalWon() % captureMinor == 0) dashboardMinor--;

        webhooks.handle(SIGNATURE, refundEvent("PAYMENT.CAPTURE.REFUNDED", "DASH-" + UUID.randomUUID(), paid.captureId(),
                BigDecimal.valueOf(dashboardMinor, 2)).replace("@EVENT@", "WH-" + UUID.randomUUID()));
        webhooks.handle(SIGNATURE, refundEvent("PAYMENT.CAPTURE.REVERSED", "REV-" + UUID.randomUUID(), paid.captureId(),
                BigDecimal.valueOf(captureMinor - ourMinor - dashboardMinor, 2))
                .replace("@EVENT@", "WH-" + UUID.randomUUID()));

        assertThat(providerRefundJournals(paid.orderId())).hasSize(3);
        assertThat(providerRefundJournals(paid.orderId()).stream().mapToLong(Long::longValue).sum())
                .isEqualTo(paid.totalWon());
        OrderRequest.Refund another = new OrderRequest.Refund(paid.sellerId(), paid.orderId(), "atlas-2",
                List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1)));
        assertThatThrownBy(() -> refunds.refund(another))
                .hasMessageContaining("Refund exceeds the captured payment amount");
        assertThat(providerRefundJournals(paid.orderId()).stream().mapToLong(Long::longValue).sum())
                .isEqualTo(paid.totalWon());
    }

    @Test
    void disputeIsHeldForReviewWithoutMovingMoney() {
        Paid paid = paidOrder(10_000);
        long before = sellerHeldWon(paid.sellerId());
        String disputeId = "PP-D-" + UUID.randomUUID();
        String body = """
                {"id":"@EVENT@","event_type":"CUSTOMER.DISPUTE.CREATED","resource_type":"dispute",
                 "resource":{"dispute_id":"%s","status":"OPEN","reason":"MERCHANDISE_OR_SERVICE_NOT_RECEIVED",
                  "dispute_amount":{"currency_code":"%s","value":"%s"},
                  "disputed_transactions":[{"seller_transaction_id":"%s"}]}}
                """.formatted(disputeId, paid.providerCurrency(), paid.providerAmount().toPlainString(),
                paid.captureId());

        webhooks.handle(SIGNATURE, body.replace("@EVENT@", "WH-" + UUID.randomUUID()));

        assertThat(providerRefundJournals(paid.orderId())).isEmpty();
        assertThat(sellerHeldWon(paid.sellerId())).isEqualTo(before);
        assertThat(jdbc.queryForList("""
                SELECT kind, status, attempt_id FROM payment_provider_adjustment WHERE provider_resource_id = ?
                """, disputeId)).containsExactly(Map.of("kind", "DISPUTE", "status", "REVIEW",
                "attempt_id", paid.attemptId()));
    }

    @Test
    void refundWebhookForOurOwnUnpricedRefundIsNotPostedAsProviderOriginated() {
        Paid paid = paidOrder(10_000);
        OrderRequest.Refund request = new OrderRequest.Refund(paid.sellerId(), paid.orderId(), "own-refund",
                List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1)));
        disconnectNextRefundResponse();
        assertThat(refunds.refund(request).paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        Map<String, Object> operation = jdbc.queryForMap("""
                SELECT provider_request_id, provider_refund_id, provider_amount_minor
                FROM payment_refund_operation WHERE attempt_id = ?
                """, paid.attemptId());
        assertThat(operation.get("provider_refund_id")).isNull();
        String providerRefundId = stubRefundIdForRequest(operation.get("provider_request_id").toString());
        BigDecimal amount = BigDecimal.valueOf((Long) operation.get("provider_amount_minor"), 2);

        // PayPal 에는 우리 환불이 생겼지만 응답이 끊겨 id 를 아직 저장하지 못했다. 이 웹훅을 외부 환불로
        // 기록하면 우리 환불이 나중에 확정될 때 같은 돈을 판매자에게서 두 번 뺀다.
        webhooks.handle(SIGNATURE, refundEvent("PAYMENT.CAPTURE.REFUNDED", providerRefundId, paid.captureId(), amount)
                .replace("@EVENT@", "WH-" + UUID.randomUUID()));
        assertThat(adjustments(providerRefundId)).isZero();

        assertThat(refunds.refund(request).paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        webhooks.handle(SIGNATURE, refundEvent("PAYMENT.CAPTURE.REFUNDED", providerRefundId, paid.captureId(), amount)
                .replace("@EVENT@", "WH-" + UUID.randomUUID()));
        assertThat(adjustments(providerRefundId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_journal WHERE source_order_id = ? AND source_refund_id IS NOT NULL
                """, Integer.class, paid.orderId())).isEqualTo(1);
    }

    /** PayPal 공식 명세(payments_payment_v2.json)의 PAYMENT.CAPTURE.REFUNDED 예시 모양: capture 는 links 의 rel=up 으로만 이어진다. */
    private static String refundEvent(String eventType, String refundId, String captureId, BigDecimal amount) {
        return """
                {"id":"@EVENT@","event_type":"%s","resource_type":"refund",
                 "resource":{"id":"%s","status":"COMPLETED","amount":{"value":"%s","currency_code":"USD"},
                  "links":[{"href":"https://api.paypal.com/v2/payments/refunds/%s","rel":"self","method":"GET"},
                           {"href":"https://api.paypal.com/v2/payments/captures/%s","rel":"up","method":"GET"}]}}
                """.formatted(eventType, refundId, amount.toPlainString(), refundId, captureId);
    }

    private Paid paidOrder(long unitPriceWon) {
        return paidOrder(unitPriceWon, 1);
    }

    private Paid paidOrder(long unitPriceWon, int quantity) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Provider event product", null));
        String skuId = "provider-event-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, unitPriceWon, quantity));
        String buyerRef = "provider-event-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, quantity);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "provider-event-checkout-" + UUID.randomUUID());
        CheckoutResponse pending = checkoutPayments.submit(checkout.checkoutId());
        OrderViewResponse.Order order = checkoutPayments.completeApproval(checkout.checkoutId(),
                approvalToken(pending.paymentApprovalUrl())).orders().get(0);
        assertThat(order.state()).isEqualTo(OrderState.PAID);
        Map<String, Object> attempt = jdbc.queryForMap("""
                SELECT attempt_id, provider_capture_id, provider_amount_minor, provider_currency, total_won
                FROM payment_attempt WHERE checkout_id = ?
                """, checkout.checkoutId());
        UUID orderItemId = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ?",
                UUID.class, order.orderId());
        return new Paid(sellerId, order.orderId(), orderItemId, (UUID) attempt.get("attempt_id"),
                (String) attempt.get("provider_capture_id"),
                BigDecimal.valueOf((Long) attempt.get("provider_amount_minor"), 2),
                (String) attempt.get("provider_currency"), (Long) attempt.get("total_won"));
    }

    /** 판매자에게 남은 몫(SELLER_PAYABLE + SELLER_RESERVE, 대변 − 차변). */
    private long sellerHeldWon(UUID sellerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END), 0)
                FROM settlement_entry
                WHERE seller_id = ? AND account_code IN ('SELLER_PAYABLE', 'SELLER_RESERVE')
                """, Long.class, sellerId);
    }

    private List<Long> providerRefundJournals(UUID orderId) {
        return jdbc.queryForList("""
                SELECT gross_amount_won FROM settlement_journal
                WHERE source_order_id = ? AND source_refund_id IS NOT NULL
                ORDER BY recorded_at, journal_id
                """, Long.class, orderId);
    }

    private int webhookRetries(String resourceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM paypal_webhook_retry WHERE resource_id = ?",
                Integer.class, resourceId);
    }

    private int adjustments(String providerResourceId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_provider_adjustment WHERE provider_resource_id = ?
                """, Integer.class, providerResourceId);
    }

    private record Paid(UUID sellerId, UUID orderId, UUID orderItemId, UUID attemptId, String captureId,
            BigDecimal providerAmount, String providerCurrency, long totalWon) {
    }
}
