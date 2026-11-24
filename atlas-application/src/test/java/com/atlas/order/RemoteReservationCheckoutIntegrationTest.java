package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.order.dto.request.OrderRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.catalog.service.SellerCouponService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.service.CartCheckoutService;
import java.time.Duration;
import java.util.List;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.LocationReservationService;
import com.atlas.order.service.CheckoutFinalizationService;
import com.atlas.order.service.LocationRestockService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderReturnService;
import com.atlas.order.service.OrderShipmentService;
import com.atlas.order.repository.LocationRestockRepository;
import com.atlas.order.persistence.projection.LocationRestock;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * 원격 location 예약을 켠 Buyer checkout의 정상·불확실·거절·취소 경로를 검증한다.
 * 원격 inventory-location은 MockWebServer로 대체하고, 주문 DB는 PostgreSQL을 사용한다.
 */
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
class RemoteReservationCheckoutIntegrationTest extends PayPalIntegrationTestSupport {
    // fixture마다 location 하나를 소비한다. 테스트 추가로 풀이 바닥나지 않도록 고정 UUID를 순번에서 생성한다.
    private static final UUID[] LOCATIONS = IntStream.rangeClosed(1, 40)
            .mapToObj(index -> new UUID(0x7000L, index)).toArray(UUID[]::new);
    private static final String SERVICE_TOKEN = "integration-checkout-service-token-32bytes";
    private static final MockWebServer INVENTORY = startServer();
    private static int nextLocation;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryCapacityAdjustmentService adjustments;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private LocationReservationService coordinator;
    @Autowired private CheckoutFinalizationService finalizer;

    @Autowired
    private LocationRestockRepository restockRepository;
    @Autowired CartCheckoutService cartCheckouts;
    @Autowired SellerCouponService coupons;
    @Autowired OrderRefundService refunds;
    @Autowired LocationRestockService restocks;
    @Autowired InventoryService inventory;
    @Autowired OrderFulfillmentService fulfillments;
    @Autowired OrderReturnService returns;
    @Autowired OrderShipmentService shipments;

    @AfterAll
    static void shutdownInventoryStub() throws IOException {
        INVENTORY.shutdown();
    }

    @BeforeEach
    void drainInventoryStub() throws InterruptedException {
        // 이전 테스트가 소비하지 않은 응답이 다음 테스트로 새지 않도록 응답 queue도 새로 만든다.
        availabilityStatus = 200;
        availableQuantity = 100;
        INVENTORY.setDispatcher(new StockAwareDispatcher());
        while (takeCommand(0, TimeUnit.MILLISECONDS) != null) {
            // 이전 테스트에서 소비되지 않은 요청을 제거한다.
        }
    }

    @AfterEach
    void discardUnconsumedInventoryRequests() throws InterruptedException {
        int idlePolls = 0;
        while (idlePolls < 3) {
            RecordedRequest request = INVENTORY.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request == null) {
                idlePolls++;
                continue;
            }
            idlePolls = 0;
            INVENTORY.enqueue(new MockResponse().setResponseCode(503));
        }
        INVENTORY.setDispatcher(new CleanupDispatcher());
    }
    @Test
    void remoteReservationPromotesCheckoutWithoutLocalReservation() throws Exception {
        Fixture fixture = fixture("remote-reserved");
        UUID remoteReservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(remoteReservationId));

        CheckoutResponse checkout = checkout(fixture.buyer());

        assertThat(checkout.state()).isEqualTo(BuyerCheckoutState.RESERVED);
        assertThat(orderStates(checkout.checkoutId())).isEqualTo("RESERVED");
        assertThat(workflowStates(checkout.checkoutId())).isEqualTo("RESERVED");
        assertThat(localReservations(fixture)).isZero();
        assertThat(cartItemCount(fixture.buyer())).isZero();
        RecordedRequest request = takeCommand(5, TimeUnit.SECONDS);
        assertThat(request.getPath()).endsWith("/fulfillment-locations/" + fixture.locationId() + "/reservations");
        assertThat(request.getBody().readUtf8()).contains(fixture.sellerId().toString(), fixture.sku());
    }

    @Test
    void expiryCancellationRetainsExpiredTargetAfterTransientFailure() throws Exception {
        Fixture fixture = fixture("expiry-recovery");
        String code = "REMOTE" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        coupons.create(fixture.sellerId(), code, 10, now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)), 1L);
        UUID remoteId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(remoteId));
        CheckoutResponse checkout = cartCheckouts.checkout(fixture.buyer(), "expiry-" + UUID.randomUUID(),
                List.of(new SellerCouponRequest(fixture.sellerId(), code)));
        jdbc.update("UPDATE purchase_order SET checkout_expires_at = now() - interval '1 second' WHERE checkout_id = ?",
                checkout.checkoutId());
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));

        checkoutPayments.expireDueCheckouts();

        assertThat(checkoutState(checkout.checkoutId())).isEqualTo("CANCEL_PENDING");
        assertThat(coupons.find(fixture.sellerId(), code).reservedCount()).isEqualTo(1L);
        INVENTORY.enqueue(cancelled(remoteId));
        makeWorkflowsDue(checkout.checkoutId());
        coordinator.recoverDue(10);
        finalizer.finalizeDue(10);
        assertThat(checkoutState(checkout.checkoutId())).isEqualTo("EXPIRED");
        assertThat(orderStates(checkout.checkoutId())).isEqualTo("EXPIRED");
        assertThat(workflowStates(checkout.checkoutId())).isEqualTo("CANCELLED");
        assertThat(coupons.find(fixture.sellerId(), code).reservedCount()).isZero();
    }

    @Test
    void unknownRemoteOutcomeKeepsCheckoutPendingUntilRecoveryConfirmsIt() throws Exception {
        Fixture fixture = fixture("remote-unknown");
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));

        CheckoutResponse pending = checkout(fixture.buyer());

        assertThat(pending.state()).isEqualTo(BuyerCheckoutState.RESERVATION_PENDING);
        assertThat(orderStates(pending.checkoutId())).isEqualTo("RESERVATION_PENDING");
        assertThat(cartItemCount(fixture.buyer())).isEqualTo(1);

        INVENTORY.enqueue(reserved(UUID.randomUUID()));
        makeWorkflowsDue(pending.checkoutId());
        assertThat(coordinator.recoverDue(10)).isEqualTo(1);
        assertThat(finalizer.finalizeDue(10)).isEqualTo(1);

        assertThat(checkoutState(pending.checkoutId())).isEqualTo("RESERVED");
        assertThat(orderStates(pending.checkoutId())).isEqualTo("RESERVED");
        assertThat(cartItemCount(fixture.buyer())).isZero();
        RecordedRequest first = takeCommand(5, TimeUnit.SECONDS);
        RecordedRequest retried = takeCommand(5, TimeUnit.SECONDS);
        assertThat(retried.getHeader("Idempotency-Key")).isEqualTo(first.getHeader("Idempotency-Key"));
    }

    @Test
    void cancellationOfPendingReservationDoesNotLeaveAReservationBehind() throws Exception {
        Fixture fixture = fixture("pending-cancel");
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));
        CheckoutResponse pending = checkout(fixture.buyer());
        assertThat(pending.state()).isEqualTo(BuyerCheckoutState.RESERVATION_PENDING);

        UUID remoteId = UUID.randomUUID();
        INVENTORY.enqueue(cancelled(remoteId));
        CheckoutResponse result = checkoutPayments.cancel(pending.checkoutId(), UUID.randomUUID().toString());

        assertThat(result.state()).isEqualTo(BuyerCheckoutState.CANCELLED);
        assertThat(orderStates(pending.checkoutId())).isEqualTo("CANCELLED");
        assertThat(workflowStates(pending.checkoutId())).isEqualTo("CANCELLED");
        assertThat(cartItemCount(fixture.buyer())).isEqualTo(1);
        takeCommand(5, TimeUnit.SECONDS);
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
    }

    @Test
    void remoteRejectionReleasesCouponForAnotherCheckout() throws Exception {
        Fixture fixture = fixture("rejected-coupon");
        String code = "REMOTE" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        coupons.create(fixture.sellerId(), code, 10, now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(1)), 1L);
        INVENTORY.enqueue(new MockResponse().setResponseCode(409)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"no stock\"}"));

        CheckoutResponse failed = cartCheckouts.checkout(fixture.buyer(), "coupon-" + UUID.randomUUID(),
                List.of(new SellerCouponRequest(fixture.sellerId(), code)));

        assertThat(failed.state()).isEqualTo(BuyerCheckoutState.FAILED);
        assertThat(coupons.find(fixture.sellerId(), code).reservedCount()).isZero();
        assertThat(coupons.find(fixture.sellerId(), code).remainingCount()).isEqualTo(1L);
        assertThat(cartItemCount(fixture.buyer())).isEqualTo(1);
        INVENTORY.enqueue(reserved(UUID.randomUUID()));
        CheckoutResponse retry = cartCheckouts.checkout(fixture.buyer(), "coupon-retry-" + UUID.randomUUID(),
                List.of(new SellerCouponRequest(fixture.sellerId(), code)));
        assertThat(retry.state()).isEqualTo(BuyerCheckoutState.RESERVED);
        assertThat(coupons.find(fixture.sellerId(), code).reservedCount()).isEqualTo(1L);
    }

    @Test
    void unconfirmedAvailabilityIsRetryableAndKeepsTheCartInsteadOfReportingNoStock() throws Exception {
        Fixture fixture = fixture("lookup-down");
        availabilityStatus = 503;

        mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", fixture.buyer())
                        .header("Idempotency-Key", "remote-checkout-" + UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));

        assertThat(cartItemCount(fixture.buyer())).isPositive();
        assertThat(takeCommand(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void confirmedZeroAvailabilityRemainsAConflictRatherThanARetryableOutage() throws Exception {
        Fixture fixture = fixture("zero-stock");
        availableQuantity = 0;

        mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", fixture.buyer())
                        .header("Idempotency-Key", "remote-checkout-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(cartItemCount(fixture.buyer())).isPositive();
        assertThat(takeCommand(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void paypalApprovalClaimsRemoteStockBeforeCaptureAndMarksPaid() throws Exception {
        Fixture fixture = fixture("remote-paid");
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        takeCommand(5, TimeUnit.SECONDS);
        int capturesBefore = captureRequestCount();
        INVENTORY.enqueue(transitioned(reservationId, "CLAIMED"));

        CheckoutResponse paid = payAndApprove(reserved.checkoutId());

        assertThat(paid.state()).isEqualTo(BuyerCheckoutState.PAID);
        assertThat(orderStates(reserved.checkoutId())).isEqualTo("PAID");
        assertThat(workflowStates(reserved.checkoutId())).isEqualTo("CLAIMED");
        assertThat(captureRequestCount() - capturesBefore).isEqualTo(1);
        assertThat(localReservations(fixture)).isZero();
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath())
                .endsWith("/reservations/" + reservationId + "/claim");
    }

    @Test
    void expiredRemoteReservationVoidsAuthorizationInsteadOfCapturing() throws Exception {
        Fixture fixture = fixture("remote-claim-expired");
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        takeCommand(5, TimeUnit.SECONDS);
        int capturesBefore = captureRequestCount();
        INVENTORY.enqueue(conflict("RESERVATION_EXPIRED"));
        INVENTORY.enqueue(cancelled(reservationId));

        CheckoutResponse failed = payAndApprove(reserved.checkoutId());

        assertThat(failed.state()).isEqualTo(BuyerCheckoutState.FAILED);
        assertThat(captureRequestCount() - capturesBefore).isZero();
        assertThat(workflowStates(reserved.checkoutId())).isEqualTo("CANCELLED");
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/reservations/cancel");
    }

    @Test
    void unknownClaimKeepsAuthorizationUntilReconcileClaimsAndCapturesOnce() throws Exception {
        Fixture fixture = fixture("remote-claim-unknown");
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        takeCommand(5, TimeUnit.SECONDS);
        int capturesBefore = captureRequestCount();
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));

        CheckoutResponse pending = payAndApprove(reserved.checkoutId());

        assertThat(pending.state()).isEqualTo(BuyerCheckoutState.PAYMENT_PENDING);
        assertThat(captureRequestCount() - capturesBefore).isZero();
        INVENTORY.enqueue(transitioned(reservationId, "CLAIMED"));

        CheckoutResponse paid = checkoutPayments.reconcile(reserved.checkoutId(), "claim-retry-" + UUID.randomUUID());
        CheckoutResponse replayed = checkoutPayments.reconcile(reserved.checkoutId(), "claim-replay-" + UUID.randomUUID());

        assertThat(paid.state()).isEqualTo(BuyerCheckoutState.PAID);
        assertThat(replayed.state()).isEqualTo(BuyerCheckoutState.PAID);
        assertThat(captureRequestCount() - capturesBefore).isEqualTo(1);
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        assertThat(takeCommand(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void unknownCaptureAfterClaimKeepsClaimedStockWithoutReleasingIt() throws Exception {
        Fixture fixture = fixture("remote-capture-unknown");
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        takeCommand(5, TimeUnit.SECONDS);
        INVENTORY.enqueue(transitioned(reservationId, "CLAIMED"));
        // PayPal adapter는 COMPLETED/PENDING 외 capture 응답을 결과 미확정으로 취급한다.
        queueCaptureStatus("DECLINED");

        CheckoutResponse unknown = payAndApprove(reserved.checkoutId());

        assertThat(unknown.state()).isEqualTo(BuyerCheckoutState.PAYMENT_UNKNOWN);
        assertThat(workflowStates(reserved.checkoutId())).isEqualTo("CLAIMED");
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        // 매입 결과가 불확실한 동안 판매 확정 재고를 되돌리면 이중 판매가 생길 수 있으므로 원격 호출을 추가하지 않는다.
        assertThat(takeCommand(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void cancellingClaimedWorkflowRestoresRemoteStock() throws Exception {
        Fixture fixture = fixture("remote-claimed-cancel");
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        takeCommand(5, TimeUnit.SECONDS);
        UUID workflowId = jdbc.queryForObject(
                "SELECT reservation_workflow_id FROM purchase_order WHERE checkout_id = ?", UUID.class,
                reserved.checkoutId());
        INVENTORY.enqueue(transitioned(reservationId, "CLAIMED"));
        assertThat(coordinator.claim(workflowId)).isEqualTo(LocationReservationService.ClaimOutcome.CLAIMED);
        assertThat(coordinator.claim(workflowId)).isEqualTo(LocationReservationService.ClaimOutcome.CLAIMED);
        INVENTORY.enqueue(transitioned(reservationId, "RELEASED"));

        assertThat(coordinator.cancel(workflowId).status()).isEqualTo("CANCELLED");

        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath()).endsWith("/claim");
        assertThat(takeCommand(5, TimeUnit.SECONDS).getPath())
                .endsWith("/reservations/" + reservationId + "/restore");
        assertThat(takeCommand(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void staleRestockClaimantCannotOverwriteTheNewerClaimsOutcome() throws Exception {
        PaidRemoteOrder paid = paidRemoteOrder("remote-refund-stale");
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));
        var refund = refunds.refund(new OrderRequest.Refund(paid.sellerId(), paid.orderId(),
                "remote-refund-stale-" + UUID.randomUUID(), List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1))));
        String restockKey = jdbc.queryForObject(
                "SELECT restock_key FROM order_inventory_restock WHERE refund_id = ?", String.class, refund.refundId());
        UUID locationId = jdbc.queryForObject(
                "SELECT fulfillment_location_id FROM order_inventory_restock WHERE refund_id = ?",
                UUID.class, refund.refundId());

        // 앞선 worker의 lease가 만료된 뒤 뒤 worker가 같은 항목을 claim한 장면이다.
        makeRestockDue(refund.refundId());
        List<LocationRestock> stale = restockRepository.claimDue(Instant.now(),
                Instant.now().plusSeconds(30), 10, null);
        assertThat(stale).hasSize(1);
        makeRestockDue(refund.refundId());
        List<LocationRestock> current = restockRepository.claimDue(Instant.now(),
                Instant.now().plusSeconds(30), 10, null);
        assertThat(current).hasSize(1);

        // 늦게 깨어난 앞선 worker가 실패로 마감해도 뒤 worker의 claim 결과를 덮어쓰면 안 된다.
        restockRepository.finish(restockKey, locationId, stale.getFirst().attemptCount(),
                "NEEDS_REVIEW", "STALE_WORKER",
                Instant.now(), Instant.now());
        assertThat(restockStates(refund.refundId())).isEqualTo("PENDING");

        restockRepository.finish(restockKey, locationId, current.getFirst().attemptCount(),
                "COMPLETED", null, Instant.now(), Instant.now());
        assertThat(restockStates(refund.refundId())).isEqualTo("COMPLETED");
    }

    @Test
    void unknownRestockOutcomeIsRetriedByRecoveryWithTheSameKey() throws Exception {
        PaidRemoteOrder paid = paidRemoteOrder("remote-refund-retry");
        INVENTORY.enqueue(new MockResponse().setResponseCode(503));

        var refund = refunds.refund(new OrderRequest.Refund(paid.sellerId(), paid.orderId(),
                "remote-refund-retry-" + UUID.randomUUID(), List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1))));

        assertThat(restockStates(refund.refundId())).isEqualTo("PENDING");
        INVENTORY.enqueue(transitioned(paid.reservationId(), "CLAIMED"));
        jdbc.update("UPDATE order_inventory_restock SET next_attempt_at = now() - interval '1 second' WHERE refund_id = ?",
                refund.refundId());
        assertThat(restocks.recoverDue(10)).isEqualTo(1);
        assertThat(restockStates(refund.refundId())).isEqualTo("COMPLETED");
        String first = takeCommand(5, TimeUnit.SECONDS).getHeader("Idempotency-Key");
        String retried = takeCommand(5, TimeUnit.SECONDS).getHeader("Idempotency-Key");
        assertThat(retried).isEqualTo(first);
    }

    @Test
    void inspectedReturnRestocksAtItsSourceShipmentReservation() throws Exception {
        PaidRemoteOrder paid = paidRemoteOrder("remote-return-restock");
        fulfillments.start(paid.sellerId(), paid.orderId());
        var shipment = shipments.create(new OrderRequest.Shipment(paid.sellerId(), paid.orderId(),
                "return-shipment-" + UUID.randomUUID(), null, null,
                List.of(new OrderRequest.Shipment.Item(paid.orderItemId(), 1))));
        shipments.deliver(paid.sellerId(), paid.orderId(), shipment.shipmentId());

        var requested = returns.request(new OrderRequest.Return(paid.buyerRef(), paid.orderId(),
                "return-request-" + UUID.randomUUID(),
                List.of(new OrderRequest.Return.Item(paid.orderItemId(), 1, shipment.shipmentId()))));
        returns.receive(paid.sellerId(), paid.orderId(), requested.returnId());
        INVENTORY.enqueue(transitioned(paid.reservationId(), "CLAIMED"));
        var inspected = returns.inspect(paid.sellerId(), paid.orderId(), requested.returnId(),
                new OrderRequest.ReturnInspection(List.of(new OrderRequest.ReturnInspection.Item(
                        paid.orderItemId(), OrderRequest.ReturnInspection.Result.PASSED))));

        assertThat(inspected.status().name()).isEqualTo("REFUND_RECORDED");
        RecordedRequest restock = takeCommand(5, TimeUnit.SECONDS);
        assertThat(restock.getPath()).endsWith("/reservations/" + paid.reservationId() + "/restock");
        assertThat(restockStates(inspected.items().getFirst().refundId())).isEqualTo("COMPLETED");
    }

    private PaidRemoteOrder paidRemoteOrder(String label) throws Exception {
        Fixture fixture = fixture(label);
        UUID reservationId = UUID.randomUUID();
        INVENTORY.enqueue(reserved(reservationId));
        CheckoutResponse reserved = checkout(fixture.buyer());
        INVENTORY.enqueue(transitioned(reservationId, "CLAIMED"));
        CheckoutResponse paid = payAndApprove(reserved.checkoutId());
        assertThat(paid.state()).isEqualTo(BuyerCheckoutState.PAID);
        takeCommand(5, TimeUnit.SECONDS);
        takeCommand(5, TimeUnit.SECONDS);
        UUID orderId = paid.orders().getFirst().orderId();
        UUID orderItemId = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, orderId);
        return new PaidRemoteOrder(fixture.sellerId(), orderId, orderItemId, reservationId, fixture.sku(),
                fixture.buyer());
    }

    private void makeRestockDue(UUID refundId) {
        jdbc.update("UPDATE order_inventory_restock SET next_attempt_at = now() - interval '1 second' "
                + "WHERE refund_id = ?", refundId);
    }

    private String restockStates(UUID refundId) {
        return jdbc.queryForObject(
                "SELECT string_agg(DISTINCT restock_state, ',') FROM order_inventory_restock WHERE refund_id = ?",
                String.class, refundId);
    }

    private record PaidRemoteOrder(UUID sellerId, UUID orderId, UUID orderItemId, UUID reservationId, String sku,
                                  String buyerRef) {
    }

    private CheckoutResponse payAndApprove(UUID checkoutId) throws Exception {
        CheckoutResponse paymentPending = mapper.readValue(mockMvc.perform(
                        post("/api/checkouts/{checkoutId}/payment", checkoutId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        String providerOrderId = paymentPending.paymentApprovalUrl()
                .substring(paymentPending.paymentApprovalUrl().lastIndexOf('/') + 1);
        return mapper.readValue(mockMvc.perform(
                        get("/api/checkouts/{checkoutId}/payment/return", checkoutId).param("token", providerOrderId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
    }

    private Fixture fixture(String label) throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        UUID locationId = LOCATIONS[nextLocation++];
        CatalogResponse.Product product = products.createProduct(sellerId,
                new ProductRequest.CreateProduct(label + "-" + UUID.randomUUID(), null));
        String sku = label + "-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(sku, 100, 0));
        jdbc.update("INSERT INTO inventory_location(location_id,seller_id,location_code,name,priority,active)"
                + " VALUES (?,?,?,?,0,TRUE)", locationId, sellerId, "REMOTE-" + UUID.randomUUID(), label);
        // 로컬 재고는 location 선택 근거로만 쓰이며, 원격 예약 경로에서는 차감되지 않아야 한다.
        adjustments.adjust(sellerId, sku, locationId, "seed-" + UUID.randomUUID(), 2, "seed");
        String buyer = label + "-buyer-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyer}/cart/items/{sellerId}/{sku}", buyer, sellerId, sku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        return new Fixture(sellerId, locationId, sku, buyer);
    }

    private CheckoutResponse checkout(String buyer) throws Exception {
        return mapper.readValue(mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", buyer)
                        .header("Idempotency-Key", "remote-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
    }

    private String checkoutState(UUID checkoutId) {
        return jdbc.queryForObject("SELECT state FROM buyer_checkout WHERE checkout_id=?", String.class, checkoutId);
    }

    private String orderStates(UUID checkoutId) {
        return jdbc.queryForObject("SELECT string_agg(DISTINCT state, ',') FROM purchase_order WHERE checkout_id=?",
                String.class, checkoutId);
    }

    private String workflowStates(UUID checkoutId) {
        return jdbc.queryForObject("""
                SELECT string_agg(DISTINCT workflow.workflow_state, ',')
                FROM purchase_order purchase
                JOIN order_inventory_reservation_workflow workflow
                  ON workflow.workflow_id = purchase.reservation_workflow_id
                WHERE purchase.checkout_id = ?
                """, String.class, checkoutId);
    }

    private void makeWorkflowsDue(UUID checkoutId) {
        jdbc.update("""
                UPDATE order_inventory_reservation_workflow
                SET next_attempt_at = now() - interval '1 second'
                WHERE workflow_id IN (SELECT reservation_workflow_id FROM purchase_order WHERE checkout_id = ?)
                """, checkoutId);
    }

    private int localReservations(Fixture fixture) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE seller_id=? AND sku_id=?",
                Integer.class, fixture.sellerId(), fixture.sku());
    }

    private int cartItemCount(String buyer) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM shopping_cart_item i JOIN shopping_cart c ON c.cart_id=i.cart_id WHERE c.buyer_ref=?",
                Integer.class, buyer);
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
        String baseUrl = INVENTORY.url("/").toString().replaceAll("/$", "");
        StringBuilder endpoints = new StringBuilder();
        for (UUID location : LOCATIONS) {
            if (!endpoints.isEmpty()) endpoints.append(';');
            endpoints.append(location).append('=').append(baseUrl);
        }
        return endpoints.toString();
    }

    private static MockResponse reserved(UUID reservationId) {
        return reserved(reservationId, Instant.now().plusSeconds(120));
    }

    private static MockResponse reserved(UUID reservationId, Instant expiresAt) {
        return new MockResponse().setResponseCode(201)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\"RESERVED\","
                        + "\"expiresAt\":\"" + expiresAt + "\"}");
    }

    private static MockResponse cancelled(UUID reservationId) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\"CANCELLED\"}");
    }

    private static MockResponse transitioned(UUID reservationId, String status) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"reservationId\":\"" + reservationId + "\",\"status\":\"" + status + "\","
                        + "\"expiresAt\":\"" + Instant.now().plusSeconds(60) + "\"}");
    }

    private static MockResponse conflict(String code) {
        return new MockResponse().setResponseCode(409)
                .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"code\":\"" + code + "\",\"message\":\"rejected\"}");
    }

    /** routing용 재고 조회(GET stock)를 건너뛰고 다음 원격 명령 요청을 반환한다. */
    private static RecordedRequest takeCommand(long timeout, TimeUnit unit) throws InterruptedException {
        while (true) {
            RecordedRequest request = INVENTORY.takeRequest(timeout, unit);
            if (request == null || !"GET".equals(request.getMethod()) || !request.getPath().contains("/availability/")) {
                return request;
            }
        }
    }

    /**
     * checkout 전 location routing을 위한 원격 재고 조회(GET stock)는 충분한 재고로 답하고,
     * 예약·claim·취소 등 명령 응답은 테스트가 넣은 순서대로 돌려준다.
     */
    private static volatile int availabilityStatus = 200;
    private static volatile int availableQuantity = 100;

    private static final class StockAwareDispatcher extends QueueDispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            if ("GET".equals(request.getMethod()) && request.getPath() != null && request.getPath().contains("/availability/")) {
                if (availabilityStatus != 200) {
                    return new MockResponse().setResponseCode(availabilityStatus);
                }
                return new MockResponse().setResponseCode(200)
                        .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .setBody("{\"available\":" + availableQuantity + "}");
            }
            return super.dispatch(request);
        }
    }

    private static final class CleanupDispatcher extends Dispatcher {
        @Override
        public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setResponseCode(503);
        }
    }

    private record Fixture(UUID sellerId, UUID locationId, String sku, String buyer) {
    }
}
