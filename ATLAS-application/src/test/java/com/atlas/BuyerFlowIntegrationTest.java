package com.atlas;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.catalog.service.ProductSalesChannel;
import com.atlas.catalog.service.ProductStatus;
import com.atlas.inventory.exception.InventoryException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.service.impl.AdmissionServiceImpl;
import com.atlas.cart.dto.response.CartResponse;
import com.atlas.cart.service.CartService;
import com.atlas.campaign.service.CampaignService;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.dto.response.SellerResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.OrderFulfillmentResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.OrderItemResponse;
import com.atlas.order.dto.response.OrderRefundResponse;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.impl.OrderServiceImpl;
import com.atlas.order.service.impl.CheckoutPaymentServiceImpl;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.application.service.PayPalWebhookService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import com.atlas.payment.persistence.state.PaymentState;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class BuyerFlowIntegrationTest extends PayPalIntegrationTestSupport {

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

    @Autowired private OrderServiceImpl orderServiceImpl;
    @Autowired private CheckoutPaymentServiceImpl checkoutLifecycle;
    @Autowired private PaymentService payments;

    @Autowired private PayPalWebhookService paypalWebhooks;
    @Autowired private AdmissionServiceImpl admissionServiceImpl;
    @Autowired private AdmissionService admission;
    @Autowired private CampaignService campaigns;
    @Autowired private SellerService sellers;
    @Autowired private CartService carts;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private OrderService orders;
    @Autowired private OrderFulfillmentService orderFulfillments;
    @Autowired private CartCheckoutService cartCheckouts;
    @Autowired private AdjustableClock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mockMvc;
@Autowired private JdbcTemplate jdbc;


    private CheckoutResponse completePayPalCheckout(CheckoutResponse pending) {
        return checkoutLifecycle.completeApproval(pending.checkoutId(), approvalToken(pending.paymentApprovalUrl()));
    }

    private CheckoutResponse completePayPalCheckout(UUID checkoutId) {
        return completePayPalCheckout(checkoutLifecycle.submit(checkoutId));
    }
    @BeforeEach
    void resetClock() {
        clock.set(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void requestValidationAndMissingResourcesUseOneApiErrorContract() throws Exception {
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerId":null,"skuId":"","queueTicketTtlSeconds":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    assertThat(result.getResponse().getContentType())
                            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    assertThat(result.getResponse().getContentAsString())
                            .contains("\"code\":\"INVALID_REQUEST\"", "\"detail\":\"요청 정보를 확인해 주세요.\"");
                });

        mockMvc.perform(get("/api/campaigns/{campaignId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CAMPAIGN_NOT_FOUND\""));
        mockMvc.perform(get("/api/campaigns/{campaignId}/tickets/{ticketId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"TICKET_NOT_FOUND\""));
        mockMvc.perform(get("/api/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"ORDER_NOT_FOUND\""));

        mockMvc.perform(get("/api/products/{productId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CATALOG_RESOURCE_NOT_FOUND\""));
        mockMvc.perform(get("/api/buyers/{buyerRef}/cart", "missing-buyer"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CART_NOT_FOUND\""));
    }

    @Test
    void campaignCheckoutRejectsProductWhenCampaignChannelIsDisabled() {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        QueueTicketResponse failedTicket = join(campaign, "buyer-product-channel", "campaign-channel-disabled");
        QueueTicketResponse waitingTicket = join(campaign, "buyer-product-channel-next", "campaign-channel-next");
        assertThat(failedTicket.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(waitingTicket.status()).isEqualTo(TicketStatus.WAITING);

        UUID productId = jdbc.queryForObject(
                "SELECT product_id FROM seller_sku WHERE seller_id = ? AND sku_id = ?",
                UUID.class, campaign.sellerId(), campaign.skuId());
        products.updateChannelAvailability(campaign.sellerId(), productId, ProductSalesChannel.CAMPAIGN, false);
        assertThatThrownBy(() -> orders.startCheckout(campaign.campaignId(), failedTicket.ticketId()))
                .isInstanceOfSatisfying(CatalogException.class, exception ->
                        assertThat(exception.code()).isEqualTo(CatalogException.Code.PRODUCT_NOT_ORDERABLE));
        assertThat(admission.find(campaign.campaignId(), failedTicket.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_FAILED);
        assertThat(admission.find(campaign.campaignId(), waitingTicket.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE ticket_id = ?",
                Integer.class, failedTicket.ticketId())).isZero();
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.reserved()).isZero();
        products.updateChannelAvailability(campaign.sellerId(), productId, ProductSalesChannel.CAMPAIGN, true);
        QueueTicketResponse rejoined = join(campaign, "buyer-product-channel", "campaign-channel-reactivated");
        assertThat(rejoined.ticketId()).isNotEqualTo(failedTicket.ticketId());
        assertThat(admission.find(campaign.campaignId(), failedTicket.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_FAILED);
    }

    @Test
    void cartAndCampaignCheckoutRaceForTheSameSingleUnitPool() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "mixed-channel-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, 1);
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 1));
        CampaignResponse campaign = openCampaign(sellerId, skuId, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "mixed-channel-" + UUID.randomUUID(),
                "mixed-channel-" + UUID.randomUUID());
        String buyerRef = "mixed-channel-cart-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);

        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CheckoutResponse> cartAttempt = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    return cartCheckouts.checkout(buyerRef, "mixed-channel-" + UUID.randomUUID());
                } catch (InventoryException exception) {
                    assertThat(exception.code()).isEqualTo(InventoryException.Code.INSUFFICIENT_STOCK);
                    return null;
                }
            });
            Future<OrderResponse> campaignAttempt = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return orders.startCheckout(campaign.campaignId(), ticket.ticketId());
            });

            CheckoutResponse cartResult = cartAttempt.get(20, TimeUnit.SECONDS);
            OrderResponse campaignResult = campaignAttempt.get(20, TimeUnit.SECONDS);
            long successfulReservations = (cartResult == null ? 0 : cartResult.orders().stream()
                    .filter(order -> order.inventoryState() == ReservationStatus.RESERVED).count())
                    + (campaignResult.inventoryState() == ReservationStatus.RESERVED ? 1 : 0);
            assertThat(successfulReservations).isEqualTo(1);
            if (campaignResult.state() == OrderState.RESERVED) {
                assertThat(campaignResult.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
            } else {
                assertThat(campaignResult.inventoryState()).isNull();
                assertThat(campaignResult.ticketState()).isEqualTo(TicketStatus.CHECKOUT_FAILED);
                assertThat(admission.find(campaign.campaignId(), ticket.ticketId()).checkoutSlotHeld()).isFalse();
            }

            InventoryPoolResponse stock = inventory.find(sellerId, skuId);
            assertConserved(stock);
            assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservation WHERE seller_id = ? AND sku_id = ? AND status = 'RESERVED'",
                    Integer.class, sellerId, skuId)).isEqualTo((int) successfulReservations);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservation WHERE seller_id = ? AND sku_id = ? AND status = 'CLAIMED'",
                    Integer.class, sellerId, skuId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservation WHERE seller_id = ? AND sku_id = ?",
                    Integer.class, sellerId, skuId)).isLessThanOrEqualTo(2);

            if (cartResult == null) {
                assertThat(campaignResult.state()).isEqualTo(OrderState.RESERVED);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                        Integer.class, buyerRef)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE buyer_ref = ?",
                        Integer.class, buyerRef)).isZero();
                assertThat(carts.find(buyerRef).items()).hasSize(1);
            } else {
                assertThat(cartResult.orders()).singleElement().satisfies(order -> {
                    assertThat(order.state()).isEqualTo(OrderState.RESERVED);
                    assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
                });
                assertThat(campaignResult.state()).isEqualTo(OrderState.FAILED);
            }
            if (campaignResult.state() == OrderState.FAILED) {
                assertThat(campaignResult.ticketState()).isEqualTo(TicketStatus.CHECKOUT_FAILED);
                assertThat(admission.find(campaign.campaignId(), ticket.ticketId()).checkoutSlotHeld()).isFalse();
            }
        }
    }

    @Test
    void reservedCartOrderCanCompleteAfterProductDeactivation() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "deactivate-cart-" + UUID.randomUUID();
        ProductResponse product = registerProductSku(sellerId, skuId, 100, 2);
        String buyerRef = "deactivate-cart-buyer-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        CheckoutResponse checkout = objectMapper.readValue(mockMvc.perform(
                        post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                                .header("Idempotency-Key", "deactivate-cart-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);
        assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        products.updateStatus(sellerId, product.productId(), ProductStatus.INACTIVE);

        CheckoutResponse paid = completePayPalCheckout(checkout.checkoutId());
        assertThat(paid.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(paid.orders()).singleElement().satisfies(paidOrder -> {
            assertThat(paidOrder.state()).isEqualTo(OrderState.PAID);
            assertThat(paidOrder.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        });
        InventoryPoolResponse stock = inventory.find(sellerId, skuId);
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(1);
    }

    @Test
    void cartCheckoutRejectsDeactivatedProductWithoutChangingCartOrInventory() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse availableProduct = products.createProduct(
                sellerId, new CreateProductRequest("orderable-cart-product", null));
        ProductResponse inactiveProduct = products.createProduct(
                sellerId, new CreateProductRequest("inactive-cart-product", null));
        String availableSku = "orderable-cart-" + UUID.randomUUID();
        String inactiveSku = "inactive-cart-" + UUID.randomUUID();
        products.registerSku(sellerId, availableProduct.productId(),
                new RegisterSellerSkuRequest(availableSku, 100, 2));
        products.registerSku(sellerId, inactiveProduct.productId(),
                new RegisterSellerSkuRequest(inactiveSku, 200, 3));
        String buyerRef = "inactive-product-cart-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, availableSku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, inactiveSku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        products.updateStatus(sellerId, inactiveProduct.productId(), ProductStatus.INACTIVE);

        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "inactive-cart-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"PRODUCT_NOT_ORDERABLE\""));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?", Integer.class, buyerRef)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order WHERE buyer_ref = ?", Integer.class, buyerRef)).isZero();
        assertThat(carts.find(buyerRef).items()).hasSize(2);
        InventoryPoolResponse availableStock = inventory.find(sellerId, availableSku);
        InventoryPoolResponse inactiveStock = inventory.find(sellerId, inactiveSku);
        assertThat(availableStock.available()).isEqualTo(2);
        assertThat(availableStock.reserved()).isZero();
        assertThat(inactiveStock.available()).isEqualTo(3);
        assertThat(inactiveStock.reserved()).isZero();
    }

    @Test
    void FIFOAdmissionAndIdempotencyReleaseWindowAtInitialPaymentSubmission() throws Exception {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        QueueTicketResponse first = join(campaign, "buyer-a", "key-a");
        QueueTicketResponse second = join(campaign, "buyer-b", "key-b");
        QueueTicketResponse replay = join(campaign, "buyer-a", "key-a");

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(second.status()).isEqualTo(TicketStatus.WAITING);
        assertThatThrownBy(() -> join(campaign, "different-buyer", "key-a"))
                .isInstanceOfSatisfying(AdmissionException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdmissionException.Code.IDEMPOTENCY_PAYLOAD_MISMATCH));

        OrderResponse order = orders.startCheckout(campaign.campaignId(), first.ticketId());
        OrderResponse checkoutReplay = orders.startCheckout(campaign.campaignId(), first.ticketId());
        assertThat(checkoutReplay.orderId()).isEqualTo(order.orderId());
assertUuidV7(campaign.sellerId());
assertUuidV7(campaign.campaignId());
assertUuidV7(first.ticketId());
assertUuidV7(order.orderId());
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);
        assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        OrderResponse paymentPending = orders.submitPayment(order.orderId());
        OrderResponse paid = orders.completePaymentApproval(order.orderId(),
                approvalToken(paymentPending.paymentApprovalUrl()));
        assertThat(paid.state()).isEqualTo(OrderState.PAID);

        OrderFulfillmentResponse fulfillment = objectMapper.readValue(
                mockMvc.perform(get("/api/orders/{orderId}/fulfillment", order.orderId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                OrderFulfillmentResponse.class);
        assertThat(fulfillment.status()).isEqualTo(FulfillmentStatus.UNFULFILLED);

        OrderResponse reconciled = orders.reconcilePayment(order.orderId(), "initial-recovery-" + order.orderId());
        assertThat(reconciled.state()).isEqualTo(OrderState.PAID);
        OrderFulfillmentResponse repeatedFulfillment = orderFulfillments.find(order.orderId());
        assertThat(repeatedFulfillment.status()).isEqualTo(FulfillmentStatus.UNFULFILLED);
        assertThat(repeatedFulfillment.createdAt()).isEqualTo(fulfillment.createdAt());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
UUID paymentOrderId = jdbc.queryForObject("SELECT order_id FROM payment_attempt WHERE order_id = ?",
UUID.class, order.orderId());
assertUuidV7(paymentOrderId);
        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
    }

    @Test
    void concurrentJoinsAllocateUniqueSequencesAndPromoteWaitingTicketsInFifoOrder() throws Exception {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        CyclicBarrier start = new CyclicBarrier(3);

        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<QueueTicketResponse> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(campaign, "buyer-race-a", "race-key-a");
            });
            Future<QueueTicketResponse> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(campaign, "buyer-race-b", "race-key-b");
            });
            Future<QueueTicketResponse> third = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(campaign, "buyer-race-c", "race-key-c");
            });

            List<QueueTicketResponse> tickets = new ArrayList<>(
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS),
                            third.get(10, TimeUnit.SECONDS)));
            tickets.sort(Comparator.comparingLong(QueueTicketResponse::sequence));

            assertThat(tickets.get(0).sequence()).isEqualTo(1);
            assertThat(tickets.get(1).sequence()).isEqualTo(2);
            assertThat(tickets.get(2).sequence()).isEqualTo(3);
            assertThat(tickets.get(0).status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
            assertThat(tickets.get(1).status()).isEqualTo(TicketStatus.WAITING);
            assertThat(tickets.get(2).status()).isEqualTo(TicketStatus.WAITING);
            assertThat(tickets).extracting(QueueTicketResponse::checkoutSlotHeld)
                    .containsExactly(true, false, false);

            admission.releaseCheckoutSlot(campaign.campaignId(), tickets.get(0).ticketId(),
                    CheckoutSlotReleaseReason.BUYER_CANCELLED);
            assertThat(admission.find(campaign.campaignId(), tickets.get(0).ticketId()).status())
                    .isEqualTo(TicketStatus.CHECKOUT_FAILED);
            assertThat(admission.find(campaign.campaignId(), tickets.get(1).ticketId()).status())
                    .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
            assertThat(admission.find(campaign.campaignId(), tickets.get(2).ticketId()).status())
                    .isEqualTo(TicketStatus.WAITING);

            admission.releaseCheckoutSlot(campaign.campaignId(), tickets.get(1).ticketId(),
                    CheckoutSlotReleaseReason.BUYER_CANCELLED);
            assertThat(admission.find(campaign.campaignId(), tickets.get(2).ticketId()).status())
                    .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        }
    }

    @Test
    void concurrentJoinWithSameIdempotencyKeyReturnsOneTicket() throws Exception {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        String buyerRef = "buyer-duplicate-join-" + UUID.randomUUID();
        String idempotencyKey = "duplicate-join-" + UUID.randomUUID();
        jdbc.update("INSERT INTO admission_counter (seller_id, sku_id, last_sequence) VALUES (?, ?, 0) "
                + "ON CONFLICT (seller_id, sku_id) DO NOTHING", campaign.sellerId(), campaign.skuId());
        CyclicBarrier start = new CyclicBarrier(3);
        try (var executor = Executors.newFixedThreadPool(2);
                var lockConnection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            lockConnection.setAutoCommit(false);
            int blockerPid;
            try (var statement = lockConnection.createStatement();
                    var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                result.next();
                blockerPid = result.getInt(1);
            }
            try (var statement = lockConnection.prepareStatement(
                    "SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ? FOR UPDATE")) {
                statement.setObject(1, campaign.sellerId());
                statement.setString(2, campaign.skuId());
                try (var result = statement.executeQuery()) {
                    result.next();
                }
            }
            Future<QueueTicketResponse> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(campaign, buyerRef, idempotencyKey);
            });
            Future<QueueTicketResponse> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(campaign, buyerRef, idempotencyKey);
            });

            start.await(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int blockedJoinCount = 0;
            while (System.nanoTime() < deadline) {
                blockedJoinCount = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE ? = ANY(pg_blocking_pids(pid))
                          AND position('FROM admission_counter' in query) > 0
                        """, Integer.class, blockerPid);
                if (blockedJoinCount == 2) {
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(blockedJoinCount).isEqualTo(2);
            lockConnection.commit();
            QueueTicketResponse firstResult = first.get(10, TimeUnit.SECONDS);
            QueueTicketResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(secondResult.ticketId()).isEqualTo(firstResult.ticketId());
            assertThat(secondResult.sequence()).isEqualTo(firstResult.sequence());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM admission_ticket WHERE campaign_id = ? AND idempotency_key = ?",
                    Integer.class, campaign.campaignId(), idempotencyKey)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT last_sequence FROM admission_counter WHERE seller_id = ? AND sku_id = ?",
                    Long.class, campaign.sellerId(), campaign.skuId())).isEqualTo(1L);
        }
    }

    @Test
    void concurrentCampaignsShareGlobalSellerSkuFifoAndCapacity() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "shared-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, 3);
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, 1));
        CampaignResponse firstCampaign = openCampaign(sellerId, skuId, Duration.ofMinutes(5));
        CampaignResponse secondCampaign = openCampaign(sellerId, skuId, Duration.ofMinutes(5));
        CyclicBarrier start = new CyclicBarrier(3);

        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<QueueTicketResponse> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(firstCampaign, "global-buyer-a", "global-key-a");
            });
            Future<QueueTicketResponse> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(secondCampaign, "global-buyer-b", "global-key-b");
            });
            Future<QueueTicketResponse> third = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return join(secondCampaign, "global-buyer-c", "global-key-c");
            });

            List<QueueTicketResponse> tickets = new ArrayList<>(
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS),
                            third.get(10, TimeUnit.SECONDS)));
            tickets.sort(Comparator.comparingLong(QueueTicketResponse::sequence));

            assertThat(tickets).extracting(QueueTicketResponse::sequence).containsExactly(1L, 2L, 3L);
            assertThat(tickets).extracting(QueueTicketResponse::status)
                    .containsExactly(TicketStatus.CHECKOUT_ACTIVE, TicketStatus.WAITING, TicketStatus.WAITING);
            assertThat(tickets.get(0).sellerId()).isEqualTo(sellerId);
            for (int index = 0; index < 2; index++) {
                QueueTicketResponse active = tickets.get(index);
                admission.releaseCheckoutSlot(active.campaignId(), active.ticketId(),
                        CheckoutSlotReleaseReason.BUYER_CANCELLED);
                QueueTicketResponse next = tickets.get(index + 1);
                assertThat(admission.find(next.campaignId(), next.ticketId()).status())
                        .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
            }
        }

        UUID anotherSellerId = sellers.createSeller().sellerId();
        registerProductSku(anotherSellerId, skuId, 100, 1);
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(anotherSellerId, skuId, 1));
        CampaignResponse otherSellerCampaign = openCampaign(anotherSellerId, skuId, Duration.ofMinutes(5));
        QueueTicketResponse otherSellerTicket = join(otherSellerCampaign, "other-seller", "other-seller-key");
        assertThat(otherSellerTicket.sequence()).isEqualTo(1);
        assertThat(otherSellerTicket.status()).isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
    }

    @Test
    void soldOutCheckoutReturnsFailureWithoutPaymentAndReleasesQueueSlot() {
        CampaignResponse campaign = openCampaign(1, 2, Duration.ofMinutes(5));
        QueueTicketResponse firstTicket = join(campaign, "buyer-first", "first-stock-key");
        QueueTicketResponse secondTicket = join(campaign, "buyer-second", "second-stock-key");
        QueueTicketResponse thirdTicket = join(campaign, "buyer-third", "third-stock-key");
        OrderResponse first = orders.startCheckout(campaign.campaignId(), firstTicket.ticketId());

        OrderResponse soldOut = orders.startCheckout(campaign.campaignId(), secondTicket.ticketId());
        assertThat(soldOut.state()).isEqualTo(OrderState.FAILED);
        assertThat(soldOut.paymentAttemptId()).isNull();
        assertThat(soldOut.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        assertThat(soldOut.ticketState()).isEqualTo(TicketStatus.CHECKOUT_FAILED);
        assertThat(orders.submitPayment(soldOut.orderId()).state()).isEqualTo(OrderState.FAILED);
        assertThat(admission.find(campaign.campaignId(), thirdTicket.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        InventoryPoolResponse held = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(held.available()).isZero();
        assertThat(held.reserved()).isEqualTo(1);
        assertConserved(held);
        OrderResponse paymentPending = orders.submitPayment(first.orderId());
        OrderResponse paid = orders.completePaymentApproval(first.orderId(),
                approvalToken(paymentPending.paymentApprovalUrl()));
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
    }

    @Test
    void unknownAuthorizationKeepsHoldThenReconcilesSameAttempt() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-recovery", "recovery-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());

        OrderResponse pending = orders.submitPayment(order.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        String providerOrderId = pending.paymentApprovalUrl()
                .substring(pending.paymentApprovalUrl().lastIndexOf('/') + 1);
        queueAuthorizationStatus("UNKNOWN");
        OrderResponse unknown = orders.completePaymentApproval(order.orderId(), providerOrderId);
        assertThat(unknown.state()).isEqualTo(OrderState.PAYMENT_UNKNOWN);
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(unknown.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(unknown.ticketState()).isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);

        OrderResponse recovered = orders.reconcilePayment(order.orderId(), "authorization-recovery-" + order.orderId());
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
    }

    @Test
    void acceptsMaximumLengthReconcileAndCancelKeys() {
        String maximumKey = "x".repeat(221);

        CampaignResponse reconcileCampaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse reconcileTicket = join(reconcileCampaign, "buyer-max-reconcile",
                "max-reconcile-ticket");
        OrderResponse reconcileOrder = orders.startCheckout(reconcileCampaign.campaignId(),
                reconcileTicket.ticketId());
        orders.submitPayment(reconcileOrder.orderId());
        assertThatCode(() -> orders.reconcilePayment(reconcileOrder.orderId(), maximumKey))
                .doesNotThrowAnyException();

        CampaignResponse cancelCampaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse cancelTicket = join(cancelCampaign, "buyer-max-cancel", "max-cancel-ticket");
        OrderResponse cancelOrder = orders.startCheckout(cancelCampaign.campaignId(), cancelTicket.ticketId());
        orders.submitPayment(cancelOrder.orderId());
        assertThatCode(() -> orders.cancel(cancelOrder.orderId(), maximumKey))
                .doesNotThrowAnyException();
    }

    @Test
    void oversizedReconcileAndCancelKeysAreRejectedBeforeProviderPersistence() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-oversized-key", "oversized-key-ticket");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        OrderResponse pending = orders.submitPayment(order.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);

        String oversizedKey = "x".repeat(222);
        assertThatThrownBy(() -> orders.reconcilePayment(order.orderId(), oversizedKey))
                .isInstanceOfSatisfying(OrderException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrderException.Code.INVALID_REQUEST));
        assertThatThrownBy(() -> orders.cancel(order.orderId(), oversizedKey))
                .isInstanceOfSatisfying(OrderException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrderException.Code.INVALID_REQUEST));

        assertThat(orders.find(order.orderId()).paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(orders.cancel(order.orderId(), "oversized-key-cleanup").state()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void cancellationVoidsRecoveredAuthorizationInsteadOfCapturingIt() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-cancel", "cancel-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());

        OrderResponse pending = orders.submitPayment(order.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        OrderResponse cancelled = orders.cancel(order.orderId(), "cancel-operation-1");
        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(cancelled.paymentState()).isEqualTo(PaymentState.VOIDED);
        assertThat(orders.cancel(order.orderId(), "cancel-operation-1")).isEqualTo(cancelled);

        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(cancelled.paymentState()).isEqualTo(PaymentState.VOIDED);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
        assertConserved(stock);
    }

    @Test
    void reconciliationEndpointExposesRecoveredPaymentAndInventoryState() throws Exception {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-api-recovery", "api-recovery-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());

        OrderResponse pending = orders.submitPayment(order.orderId());
        String providerOrderId = pending.paymentApprovalUrl()
                .substring(pending.paymentApprovalUrl().lastIndexOf('/') + 1);
        queueAuthorizationStatus("UNKNOWN");
        OrderResponse unknown = orders.completePaymentApproval(order.orderId(), providerOrderId);
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        mockMvc.perform(post("/api/orders/{orderId}/reconcile", order.orderId())
                        .header("Idempotency-Key", "reconcile-" + order.orderId()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"state\":\"PAID\"", "\"paymentState\":\"CAPTURED\"",
                                "\"paymentAttemptId\":\"" + unknown.paymentAttemptId() + "\""));

        OrderResponse recovered = orders.find(order.orderId());
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertThat(orders.reconcilePayment(order.orderId(), "reconcile-" + order.orderId()))
                .isEqualTo(recovered);
        assertThat(inventory.find(campaign.sellerId(), campaign.skuId()).claimed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Long.class, recovered.paymentAttemptId())).isEqualTo(1L);
        assertConserved(stock);

        mockMvc.perform(post("/api/orders/{orderId}/reconcile", UUID.randomUUID())
                        .header("Idempotency-Key", "missing-order-key"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"ORDER_NOT_FOUND\""));
    }

    @Test
    void cancellationEndpointVoidsPendingAuthorizationAndReleasesInventory() throws Exception {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-api-cancel", "api-cancel-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());

        orders.submitPayment(order.orderId());
        mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId())
                        .header("Idempotency-Key", "api-cancel-" + order.orderId()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"state\":\"CANCELLED\"", "\"paymentState\":\"VOIDED\""));

        OrderResponse cancelled = orders.find(order.orderId());
        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
        assertConserved(stock);

        mockMvc.perform(post("/api/orders/{orderId}/cancel", UUID.randomUUID())
                        .header("Idempotency-Key", "missing-cancel-key"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"ORDER_NOT_FOUND\""));
    }

    @Test
    void scheduledAdmissionExpiryRemainsInternalAndExpiresDueTickets() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-queue-expiry", "queue-expiry-key");
        clock.advance(Duration.ofMinutes(6));

        admissionServiceImpl.expireDueTickets();
        assertThat(admission.find(campaign.campaignId(), ticket.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
    }

    @Test
    void unsubmittedCheckoutExpiryReleasesHoldAndExpiresTicket() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-expiry", "expiry-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        clock.advance(Duration.ofMinutes(10));
        orderServiceImpl.expireDueCheckouts();

        OrderResponse expired = orders.find(order.orderId());
        assertThat(expired.state()).isEqualTo(OrderState.EXPIRED);
        assertThat(expired.paymentAttemptId()).isNull();
        assertThat(expired.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        assertThat(expired.inventoryState()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(expired.ticketState()).isEqualTo(TicketStatus.EXPIRED);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertConserved(stock);
    }

    @Test
    void buyerCannotStartAnotherPaymentWhileAnEarlierCampaignAttemptIsUnresolved() throws Exception {
        CampaignResponse campaign = openCampaign(2, 2, Duration.ofMinutes(5));
        QueueTicketResponse firstTicket = join(campaign, "same-buyer", "first-key");
        QueueTicketResponse secondTicket = join(campaign, "same-buyer", "second-key");
        OrderResponse first = orders.startCheckout(campaign.campaignId(), firstTicket.ticketId());
        OrderResponse second = orders.startCheckout(campaign.campaignId(), secondTicket.ticketId());
        OrderResponse firstPending = objectMapper.readValue(mockMvc.perform(post("/api/orders/{orderId}/payment", first.orderId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), OrderResponse.class);
        assertThat(firstPending.state()).isEqualTo(OrderState.PAYMENT_PENDING);
        assertThat(firstPending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);

        mockMvc.perform(post("/api/orders/{orderId}/payment", second.orderId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        String firstProviderOrderId = firstPending.paymentApprovalUrl()
                .substring(firstPending.paymentApprovalUrl().lastIndexOf('/') + 1);
        OrderResponse firstPaid = orders.completePaymentApproval(first.orderId(), firstProviderOrderId);
        assertThat(firstPaid.state()).isEqualTo(OrderState.PAID);
        assertThat(firstPaid.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(orders.find(first.orderId()).state()).isEqualTo(OrderState.PAID);

        OrderResponse secondPending = orders.submitPayment(second.orderId());
        String secondProviderOrderId = secondPending.paymentApprovalUrl()
                .substring(secondPending.paymentApprovalUrl().lastIndexOf('/') + 1);
        OrderResponse secondPaid = orders.completePaymentApproval(second.orderId(), secondProviderOrderId);
        assertThat(secondPaid.state()).isEqualTo(OrderState.PAID);
        assertThat(secondPaid.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(orders.find(second.orderId()).state()).isEqualTo(OrderState.PAID);
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(2);
        assertConserved(stock);
    }

    @Test
    void salePriceSnapshotFlowsThroughCheckoutCaptureAndRefundAfterWindowCloses() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "sale-" + UUID.randomUUID();
        ProductResponse product = registerProductSku(sellerId, skuId, 1000, 1);
        Instant startsAt = clock.instant().plus(Duration.ofMinutes(5));
        Instant endsAt = startsAt.plus(Duration.ofMinutes(5));
        products.setSkuSalePrice(sellerId, product.productId(), skuId, 800, startsAt, endsAt);
        String buyerRef = "buyer-sale-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        assertThat(carts.find(buyerRef).items()).singleElement()
                .satisfies(item -> assertThat(item.priceWon()).isEqualTo(1000));

        clock.set(startsAt);
        assertThat(carts.find(buyerRef).items()).singleElement()
                .satisfies(item -> assertThat(item.priceWon()).isEqualTo(800));
        CheckoutResponse checkout = cartCheckouts.checkout(buyerRef, "sale-checkout-" + UUID.randomUUID());
        OrderResponse reserved = checkout.orders().getFirst();
        assertThat(checkout.totalWon()).isEqualTo(800);
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, reserved.orderId());
        assertThat(jdbc.queryForObject(
                "SELECT unit_price_won FROM order_item WHERE order_item_id = ?", Long.class, orderItemId))
                .isEqualTo(800L);

        CheckoutResponse paid = completePayPalCheckout(checkout.checkoutId());
        OrderResponse paidOrder = paid.orders().getFirst();
        assertThat(paidOrder.state()).isEqualTo(OrderState.PAID);
        assertThat(jdbc.queryForObject(
                "SELECT gross_amount_won FROM settlement_journal WHERE checkout_id = ?",
                Long.class, checkout.checkoutId())).isEqualTo(800L);

        clock.set(endsAt);
        assertThat(products.findProduct(product.productId()).variants().getFirst().effectivePriceWon())
                .isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT unit_price_won FROM order_item WHERE order_item_id = ?", Long.class, orderItemId))
                .isEqualTo(800L);

        String refundBody = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        sellerId, paidOrder.orderId())
                        .header("Idempotency-Key", "sale-refund-" + paidOrder.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmountWon").value(800))
                .andReturn().getResponse().getContentAsString();
        OrderRefundResponse refund = objectMapper.readValue(refundBody, OrderRefundResponse.class);
        assertThat(jdbc.queryForObject(
                "SELECT gross_amount_won FROM settlement_journal WHERE source_refund_id = ?",
                Long.class, refund.refundId())).isEqualTo(800L);
        InventoryPoolResponse restored = inventory.find(sellerId, skuId);
        assertThat(restored.available()).isEqualTo(1);
        assertThat(restored.reserved()).isZero();
        assertThat(restored.claimed()).isZero();
        assertConserved(restored);
    }
    @Test
    void sellerCouponsAllocatePerSellerAndPartialRefundUsesOrderSnapshot() throws Exception {
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        String skuA1 = "coupon-a1-" + UUID.randomUUID();
        String skuA2 = "coupon-a2-" + UUID.randomUUID();
        String skuB = "coupon-b-" + UUID.randomUUID();
        registerProductSku(sellerA, skuA1, 1000, 2);
        registerProductSku(sellerA, skuA2, 2000, 1);
        registerProductSku(sellerB, skuB, 500, 1);
        Instant startsAt = clock.instant();
        Instant endsAt = clock.instant().plus(Duration.ofMinutes(10));
        record SellerCouponSeed(UUID sellerId, String code, long amountWon) { }
        for (var coupon : List.of(
                new SellerCouponSeed(sellerA, "seller-a-101", 101),
                new SellerCouponSeed(sellerB, "seller-b-50", 50))) {
            mockMvc.perform(post("/api/sellers/{sellerId}/coupons", coupon.sellerId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","discountAmountWon":%d,"startsAt":"%s","endsAt":"%s"}
                                    """.formatted(coupon.code(), coupon.amountWon(), startsAt, endsAt)))
                    .andExpect(status().isCreated());
        }

        String buyerRef = "coupon-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerA, buyerRef, skuA1, 2);
        carts.setItemQuantity(sellerA, buyerRef, skuA2, 1);
        carts.setItemQuantity(sellerB, buyerRef, skuB, 1);
        String checkoutKey = "coupon-checkout-" + UUID.randomUUID();
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", checkoutKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerCoupons":[
                                  {"sellerId":"%s","code":"SELLER-A-101"},
                                  {"sellerId":"%s","code":"seller-b-50"}
                                ]}
                                """.formatted(sellerA, sellerB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", checkoutKey))
                .andExpect(status().isConflict());
        assertThat(checkout.totalWon()).isEqualTo(4349);
        assertThat(checkout.orders()).hasSize(2);
        OrderResponse orderA = checkout.orders().stream()
                .filter(order -> order.items().getFirst().sellerId().equals(sellerA)).findFirst().orElseThrow();
        OrderResponse orderB = checkout.orders().stream()
                .filter(order -> order.items().getFirst().sellerId().equals(sellerB)).findFirst().orElseThrow();
        assertThat(orderA.couponDiscountWon()).isEqualTo(101);
        assertThat(orderA.totalPriceWon()).isEqualTo(3899);
        assertThat(orderB.couponDiscountWon()).isEqualTo(50);
        assertThat(orderB.totalPriceWon()).isEqualTo(450);
        assertThat(orderA.items()).extracting(OrderItemResponse::couponDiscountWon).containsExactly(51L, 50L);
        assertThat(orderA.items()).extracting(OrderItemResponse::lineTotalWon).containsExactly(1949L, 1950L);

        CheckoutResponse captured = completePayPalCheckout(checkout.checkoutId());
        assertThat(captured.totalWon()).isEqualTo(4349);
        assertThat(jdbc.queryForObject("SELECT gross_amount_won FROM settlement_journal WHERE checkout_id = ?",
                Long.class, checkout.checkoutId())).isEqualTo(4349L);
        mockMvc.perform(delete("/api/sellers/{sellerId}/coupons/{code}", sellerA, "seller-a-101"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", checkoutKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerCoupons":[
                                  {"sellerId":"%s","code":"SELLER-A-101"},
                                  {"sellerId":"%s","code":"seller-b-50"}
                                ]}
                                """.formatted(sellerA, sellerB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWon").value(4349));

        UUID firstItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, orderA.orderId(), skuA1);
        assertThat(jdbc.queryForObject(
                "SELECT coupon_discount_won FROM order_item WHERE order_item_id = ?", Long.class, firstItemId))
                .isEqualTo(51L);
        String firstRefundJson = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        sellerA, orderA.orderId())
                        .header("Idempotency-Key", "coupon-refund-first-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(firstItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmountWon").value(974))
                .andReturn().getResponse().getContentAsString();
        OrderRefundResponse firstRefund = objectMapper.readValue(firstRefundJson, OrderRefundResponse.class);
        assertThat(jdbc.queryForObject(
                "SELECT gross_amount_won FROM settlement_journal WHERE source_refund_id = ?",
                Long.class, firstRefund.refundId())).isEqualTo(974L);
        String secondRefundJson = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        sellerA, orderA.orderId())
                        .header("Idempotency-Key", "coupon-refund-second-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(firstItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmountWon").value(975))
                .andReturn().getResponse().getContentAsString();
        OrderRefundResponse secondRefund = objectMapper.readValue(secondRefundJson, OrderRefundResponse.class);
        assertThat(jdbc.queryForObject(
                "SELECT gross_amount_won FROM settlement_journal WHERE source_refund_id = ?",
                Long.class, secondRefund.refundId())).isEqualTo(975L);
    }

    @Test
    void sellerCouponCannotMakeAnyItemUnitRefundableForZeroWon() throws Exception {
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
        UUID sellerId = sellers.createSeller().sellerId();
        String skuA = "coupon-zero-a-" + UUID.randomUUID();
        String skuB = "coupon-zero-b-" + UUID.randomUUID();
        registerProductSku(sellerId, skuA, 100, 2);
        registerProductSku(sellerId, skuB, 1, 1);
        Instant startsAt = clock.instant().minusSeconds(1);
        Instant endsAt = clock.instant().plus(Duration.ofMinutes(10));
        mockMvc.perform(post("/api/sellers/{sellerId}/coupons", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LOW-REFUND","discountAmountWon":200,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, endsAt)))
                .andExpect(status().isCreated());

        String buyerRef = "coupon-zero-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuA, 2);
        carts.setItemQuantity(sellerId, buyerRef, skuB, 1);
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "coupon-zero-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerCoupons":[{"sellerId":"%s","code":"LOW-REFUND"}]}
                                """.formatted(sellerId)))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isZero();
        assertThat(carts.find(buyerRef).items()).hasSize(2);
    }

    @Test
    void sellerCouponCannotStackWithAnActiveSkuSalePrice() throws Exception {
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "coupon-sale-" + UUID.randomUUID();
        ProductResponse product = registerProductSku(sellerId, skuId, 1000, 1);
        Instant startsAt = clock.instant().minusSeconds(1);
        Instant endsAt = clock.instant().plus(Duration.ofMinutes(10));
        products.setSkuSalePrice(sellerId, product.productId(), skuId, 800, startsAt, endsAt);
        mockMvc.perform(post("/api/sellers/{sellerId}/coupons", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NO-STACK","discountAmountWon":100,"startsAt":"%s","endsAt":"%s"}
                                """.formatted(startsAt, endsAt)))
                .andExpect(status().isCreated());

        String buyerRef = "coupon-sale-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "coupon-sale-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sellerCoupons":[{"sellerId":"%s","code":"NO-STACK"}]}
                                """.formatted(sellerId)))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_reservation WHERE sku_id = ?",
                Integer.class, skuId)).isZero();
        assertThat(carts.find(buyerRef).items()).hasSize(1);
    }

    @Test
    void ticketLookupDoesNotExpireTicketsOrPromoteWaitingBuyers() {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        QueueTicketResponse first = join(campaign, "buyer-read-a", "read-key-a");
        clock.advance(Duration.ofMinutes(4));
        QueueTicketResponse second = join(campaign, "buyer-read-b", "read-key-b");
        clock.advance(Duration.ofMinutes(2));

        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.WAITING);

        admissionServiceImpl.expireDueTickets();
        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
    }

    @Test
    void releasingAnExpiredTicketPreservesExpiryAndPromotesTheNextLiveTicket() {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(2));
        QueueTicketResponse expired = join(campaign, "buyer-expired-release", "expired-release-key");
        clock.advance(Duration.ofMinutes(1));
        QueueTicketResponse waiting = join(campaign, "buyer-waiting-release", "waiting-release-key");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        admission.releaseCheckoutSlot(campaign.campaignId(), expired.ticketId(), CheckoutSlotReleaseReason.BUYER_CANCELLED);

        assertThat(admission.find(campaign.campaignId(), expired.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), waiting.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
    }

    @Test
    void expiredQueueTicketRemainsAnIdempotentTombstoneAndNewKeyRejoinsAtTail() {
        CampaignResponse campaign = openCampaign(2, 1, Duration.ofMinutes(2));
        QueueTicketResponse first = join(campaign, "buyer-reentry", "first-key");
        clock.advance(Duration.ofMinutes(1));
        QueueTicketResponse second = join(campaign, "buyer-next", "second-key");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        admissionServiceImpl.expireDueTickets();

        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(join(campaign, "buyer-reentry", "first-key").ticketId()).isEqualTo(first.ticketId());
        QueueTicketResponse reentry = join(campaign, "buyer-reentry", "new-key");
        assertThat(reentry.sequence()).isEqualTo(3);
        assertThat(reentry.status()).isEqualTo(TicketStatus.WAITING);
    }

    @Test
    void sellerCanRemoveAndClearCartItemsWithoutChangingOtherCartsOrInventory() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        UUID otherSellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("clear-cart", null));
        ProductResponse otherProduct = products.createProduct(otherSellerId,
                new CreateProductRequest("other-clear-cart", null));
        String firstSku = "clear-a-" + UUID.randomUUID();
        String secondSku = "clear-b-" + UUID.randomUUID();
        String otherSellerSku = "other-clear-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 4));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 3));
        products.registerSku(otherSellerId, otherProduct.productId(),
                new RegisterSellerSkuRequest(otherSellerSku, 300, 5));
        String buyerRef = "clear-buyer-" + UUID.randomUUID();
        String otherBuyerRef = "clear-other-buyer-" + UUID.randomUUID();

        for (String skuId : List.of(firstSku, secondSku)) {
            mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                            buyerRef, sellerId, skuId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":1}
                                    """))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        otherBuyerRef, sellerId, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, otherSellerId, otherSellerSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku))
                .andExpect(status().isNoContent());
        CartResponse afterSingleRemoval = objectMapper.readValue(
                mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                CartResponse.class);
        assertThat(afterSingleRemoval.items()).extracting(item -> item.skuId())
                .containsExactlyInAnyOrder(secondSku, otherSellerSku);

        mockMvc.perform(delete("/api/buyers/{buyerRef}/cart/items", buyerRef))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/buyers/{buyerRef}/cart/items", buyerRef))
                .andExpect(status().isNoContent());
        CartResponse cleared = objectMapper.readValue(
                mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                CartResponse.class);
        CartResponse otherBuyerCart = objectMapper.readValue(
                mockMvc.perform(get("/api/buyers/{buyerRef}/cart", otherBuyerRef))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                CartResponse.class);

        assertThat(cleared.items()).isEmpty();
        assertThat(otherBuyerCart.items()).extracting(item -> item.skuId()).containsExactly(secondSku);
        mockMvc.perform(delete("/api/buyers/{buyerRef}/cart/items",
                        sellerId, "missing-" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order WHERE seller_id IN (?, ?)",
                Integer.class, sellerId, otherSellerId)).isZero();
        List<InventoryPoolResponse> stock = List.of(
                inventory.find(sellerId, firstSku),
                inventory.find(sellerId, secondSku),
                inventory.find(otherSellerId, otherSellerSku));
        assertThat(stock).extracting(InventoryPoolResponse::available).containsExactly(4L, 3L, 5L);
        assertThat(stock).extracting(InventoryPoolResponse::reserved).containsExactly(0L, 0L, 0L);
        assertThat(stock).extracting(InventoryPoolResponse::claimed).containsExactly(0L, 0L, 0L);
        stock.forEach(BuyerFlowIntegrationTest::assertConserved);
    }


    @Test
    void concurrentFirstCartUpdatesAcrossSellersCreateOneBuyerCart() throws Exception {
        UUID firstSeller = sellers.createSeller().sellerId();
        UUID secondSeller = sellers.createSeller().sellerId();
        ProductResponse firstProduct = products.createProduct(firstSeller,
                new CreateProductRequest("first-cart-race-a", null));
        ProductResponse secondProduct = products.createProduct(secondSeller,
                new CreateProductRequest("first-cart-race-b", null));
        String skuId = "first-cart-race-" + UUID.randomUUID();
        products.registerSku(firstSeller, firstProduct.productId(),
                new RegisterSellerSkuRequest(skuId, 100, 2));
        products.registerSku(secondSeller, secondProduct.productId(),
                new RegisterSellerSkuRequest(skuId, 200, 2));
        String buyerRef = "first-cart-race-buyer-" + UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(3);
        var workers = Executors.newFixedThreadPool(2);
        try {
            Future<CartResponse> firstUpdate = workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return carts.setItemQuantity(firstSeller, buyerRef, skuId, 1);
            });
            Future<CartResponse> secondUpdate = workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return carts.setItemQuantity(secondSeller, buyerRef, skuId, 1);
            });
            start.await(10, TimeUnit.SECONDS);
            firstUpdate.get(10, TimeUnit.SECONDS);
            secondUpdate.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        CartResponse cart = carts.find(buyerRef);
        assertThat(cart.items()).hasSize(2);
        assertThat(cart.items()).extracting(item -> item.sellerId())
                .containsExactlyInAnyOrder(firstSeller, secondSeller);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shopping_cart WHERE buyer_ref = ?", Integer.class, buyerRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM shopping_cart_item item
                JOIN shopping_cart cart ON cart.cart_id = item.cart_id
                WHERE cart.buyer_ref = ?
                """, Integer.class, buyerRef)).isEqualTo(2);
    }

    @Test
    void sellerCartCheckoutSnapshotsCurrentPriceAndReservesAllSkuLines() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("cart-product", null));
        String firstSku = "cart-a-" + UUID.randomUUID();
        String secondSku = "cart-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 4));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 3));
        String buyerRef = "cart-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());

        products.updateSkuPrice(sellerId, product.productId(), firstSku, 125);
        String idempotencyKey = "cart-checkout-" + UUID.randomUUID();
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();

        assertThat(order.source().name()).isEqualTo("CART");
        assertThat(order.campaignId()).isNull();
        assertThat(order.ticketId()).isNull();
        assertThat(order.totalPriceWon()).isEqualTo(450);
        assertThat(order.items()).extracting(item -> item.skuId()).containsExactly(firstSku, secondSku);
        assertThat(order.items()).extracting(item -> item.unitPriceWon()).containsExactly(125L, 200L);
        assertThat(order.items()).extracting(item -> item.quantity()).containsExactly(2, 1);
        assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);

        InventoryPoolResponse firstStock = inventory.find(sellerId, firstSku);
        assertThat(firstStock.available()).isEqualTo(2);
        assertThat(firstStock.reserved()).isEqualTo(2);
        assertConserved(firstStock);
        InventoryPoolResponse secondStock = inventory.find(sellerId, secondSku);
        assertThat(secondStock.available()).isEqualTo(2);
        assertThat(secondStock.reserved()).isEqualTo(1);
        assertConserved(secondStock);

        mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"items\":[]"));
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(checkout.checkoutId().toString()));
        clock.advance(Duration.ofMinutes(11));
        orderServiceImpl.expireDueCheckouts();
    }

    @Test
    void cartCheckoutRollsBackEveryReservationWhenOneSkuIsUnavailable() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("limited-cart", null));
        String availableSku = "available-" + UUID.randomUUID();
        String scarceSku = "scarce-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(availableSku, 100, 1));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(scarceSku, 200, 1));
        String buyerRef = "rollback-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, availableSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "failed-checkout-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"INSUFFICIENT_STOCK\""));

        assertThat(inventory.find(sellerId, availableSku).available()).isEqualTo(1);
        assertThat(inventory.find(sellerId, availableSku).reserved()).isZero();
        assertThat(inventory.find(sellerId, scarceSku).available()).isEqualTo(1);
        assertThat(inventory.find(sellerId, scarceSku).reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isZero();
        mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(availableSku, scarceSku));
    }

    @Test
    void buyerWideCheckoutAllocatesSameSkuAcrossSellersAndClearsBuyerCart() throws Exception {
        UUID firstSeller = sellers.createSeller().sellerId();
        UUID secondSeller = sellers.createSeller().sellerId();
        ProductResponse firstProduct = products.createProduct(firstSeller, new CreateProductRequest("same-sku-a", null));
        ProductResponse secondProduct = products.createProduct(secondSeller, new CreateProductRequest("same-sku-b", null));
        String sku = "shared-" + UUID.randomUUID();
        products.registerSku(firstSeller, firstProduct.productId(), new RegisterSellerSkuRequest(sku, 100, 2));
        products.registerSku(secondSeller, secondProduct.productId(), new RegisterSellerSkuRequest(sku, 250, 3));
        String buyerRef = "multi-seller-" + UUID.randomUUID();
        for (UUID sellerId : List.of(firstSeller, secondSeller)) {
            mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, sku)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                    .andExpect(status().isOk());
        }
        String json = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "multi-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(json, CheckoutResponse.class);
        assertThat(checkout.orders()).hasSize(2);
        assertThat(checkout.orders()).extracting(OrderResponse::items).hasSize(2);
        assertThat(jdbc.queryForList("SELECT seller_id FROM purchase_order WHERE checkout_id = ?", UUID.class,
                checkout.checkoutId())).containsExactlyInAnyOrder(firstSeller, secondSeller);
        assertThat(inventory.find(firstSeller, sku).reserved()).isEqualTo(1);
        assertThat(inventory.find(secondSeller, sku).reserved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE checkout_id = ?",
                Integer.class, checkout.checkoutId())).isEqualTo(2);
        assertThat(objectMapper.readTree(mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("items").size()).isZero();

        CheckoutResponse paid = completePayPalCheckout(checkout.checkoutId());
        assertThat(paid.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(paid.orders()).allSatisfy(order -> {
            assertThat(order.state()).isEqualTo(OrderState.PAID);
            assertThat(order.paymentState()).isEqualTo(PaymentState.CAPTURED);
            assertThat(order.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        });
        assertThat(inventory.find(firstSeller, sku).available()).isEqualTo(1);
        assertThat(inventory.find(secondSeller, sku).available()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_journal WHERE checkout_id = ?",
                Integer.class, checkout.checkoutId())).isEqualTo(1);
        UUID journalId = jdbc.queryForObject("SELECT journal_id FROM settlement_journal WHERE checkout_id = ?",
                UUID.class, checkout.checkoutId());
        assertThat(jdbc.queryForList("SELECT account_code || ':' || entry_side || ':' || amount_won " +
                        "FROM settlement_entry WHERE journal_id = ? ORDER BY account_code", String.class, journalId))
                .containsExactly("PSP_CLEARING:DEBIT:350", "SELLER_PAYABLE:CREDIT:350");
        List<Map<String, Object>> allocations = jdbc.queryForList(
                "SELECT seller_id, amount_won FROM settlement_seller_allocation " +
                        "WHERE journal_id = ? ORDER BY allocation_ordinal", journalId);
        assertThat(allocations).hasSize(2);
        List<UUID> sellerOrder = Stream.of(firstSeller, secondSeller).sorted().toList();
        assertThat(allocations).extracting(row -> row.get("seller_id")).containsExactlyElementsOf(sellerOrder);
        assertThat(allocations.stream().mapToLong(row -> ((Number) row.get("amount_won")).longValue()).sum())
                .isEqualTo(checkout.totalWon());
        assertThat(allocations.stream().map(row -> ((Number) row.get("amount_won")).longValue()).toList())
                .containsExactly(sellerOrder.getFirst().equals(firstSeller) ? 100L : 250L,
                        sellerOrder.getLast().equals(firstSeller) ? 100L : 250L);
    }

    @Test
    void unknownMultiSellerCheckoutHoldsEveryReservationAndReconcilesSameAttempt() throws Exception {
        UUID firstSeller = sellers.createSeller().sellerId();
        UUID secondSeller = sellers.createSeller().sellerId();
        ProductResponse firstProduct = products.createProduct(firstSeller,
                new CreateProductRequest("unknown-multi-a", null));
        ProductResponse secondProduct = products.createProduct(secondSeller,
                new CreateProductRequest("unknown-multi-b", null));
        String sku = "unknown-multi-" + UUID.randomUUID();
        products.registerSku(firstSeller, firstProduct.productId(),
                new RegisterSellerSkuRequest(sku, 100, 2));
        products.registerSku(secondSeller, secondProduct.productId(),
                new RegisterSellerSkuRequest(sku, 250, 3));
        String buyerRef = "unknown-multi-buyer-" + UUID.randomUUID();
        for (UUID sellerId : List.of(firstSeller, secondSeller)) {
            mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                    buyerRef, sellerId, sku)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                    .andExpect(status().isOk());
        }
        CheckoutResponse checkout = objectMapper.readValue(mockMvc.perform(
                post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "unknown-multi-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CheckoutResponse.class);
        assertThat(checkout.orders()).hasSize(2);

        CheckoutResponse unknown = objectMapper.readValue(mockMvc.perform(
                post("/api/checkouts/{checkoutId}/payment", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                CheckoutResponse.class);
        assertThat(unknown.state().name()).isEqualTo("PAYMENT_PENDING");
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(unknown.paymentAttemptId()).isNotNull();
        List<OrderResponse> unknownOrders = unknown.orders();
        assertThat(unknownOrders).hasSize(2);
        assertThat(unknownOrders).allSatisfy(order -> {
            assertThat(order.state()).isEqualTo(OrderState.PAYMENT_PENDING);
            assertThat(order.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
            assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        });
        for (UUID sellerId : List.of(firstSeller, secondSeller)) {
            InventoryPoolResponse held = inventory.find(sellerId, sku);
            assertThat(held.reserved()).isEqualTo(1);
            assertThat(held.claimed()).isZero();
            assertConserved(held);
        }
        clock.advance(Duration.ofMinutes(11));
        checkoutLifecycle.expireDueCheckouts();
        CheckoutResponse heldCheckout = checkoutLifecycle.find(checkout.checkoutId());
        assertThat(heldCheckout.state()).isEqualTo(BuyerCheckoutState.PAYMENT_PENDING);
        for (UUID sellerId : List.of(firstSeller, secondSeller)) {
            assertThat(inventory.find(sellerId, sku).reserved()).isEqualTo(1);
        }


        String providerOrderId = unknown.paymentApprovalUrl()
                .substring(unknown.paymentApprovalUrl().lastIndexOf('/') + 1);
        CheckoutResponse recovered = checkoutLifecycle.completeApproval(checkout.checkoutId(), providerOrderId);
        assertThat(recovered.state().name()).isEqualTo("PAID");
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        assertThat(recovered.orders()).hasSize(2);
        assertThat(recovered.orders()).allSatisfy(order -> {
            assertThat(order.state()).isEqualTo(OrderState.PAID);
            assertThat(order.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
            assertThat(order.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        });
        for (UUID sellerId : List.of(firstSeller, secondSeller)) {
            InventoryPoolResponse claimed = inventory.find(sellerId, sku);
            assertThat(claimed.available()).isEqualTo(sellerId.equals(firstSeller) ? 1 : 2);
            assertThat(claimed.reserved()).isZero();
            assertThat(claimed.claimed()).isEqualTo(1);
            assertConserved(claimed);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE checkout_id = ?",
                Integer.class, checkout.checkoutId())).isEqualTo(1);
        for (OrderResponse order : recovered.orders()) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM settlement_seller_allocation WHERE order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);
        }
    }
    @Test
    void concurrentSameKeyCartCheckoutCreatesOneCheckoutAndReservation() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "same-key-checkout-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, 2);
        String buyerRef = "same-key-checkout-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        String idempotencyKey = "same-key-checkout-" + UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<CheckoutResponse> firstAttempt = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return cartCheckouts.checkout(buyerRef, idempotencyKey);
            });
            Future<CheckoutResponse> secondAttempt = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return cartCheckouts.checkout(buyerRef, idempotencyKey);
            });

            CheckoutResponse first = firstAttempt.get(20, TimeUnit.SECONDS);
            CheckoutResponse second = secondAttempt.get(20, TimeUnit.SECONDS);
            assertThat(second.checkoutId()).isEqualTo(first.checkoutId());
            assertThat(second.orders()).extracting(OrderResponse::orderId)
                    .containsExactlyElementsOf(first.orders().stream().map(OrderResponse::orderId).toList());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                    Integer.class, buyerRef)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE checkout_id = ?",
                    Integer.class, first.checkoutId())).isEqualTo(1);
            OrderResponse order = first.orders().getFirst();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM inventory_reservation WHERE order_id = ? AND status = 'RESERVED'",
                    Integer.class, order.orderId())).isEqualTo(1);
            assertThat(carts.find(buyerRef).items()).isEmpty();

            InventoryPoolResponse stock = inventory.find(sellerId, skuId);
            assertThat(stock.available()).isEqualTo(1);
            assertThat(stock.reserved()).isEqualTo(1);
            assertThat(stock.claimed()).isZero();
            assertConserved(stock);
        }
    }

    @Test
    void buyerCheckoutReplayRequiresUnchangedCartFacts() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("checkout-idempotency", null));
        String skuId = "idempotent-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 150, 4));
        String buyerRef = "checkout-idempotency-" + UUID.randomUUID();
        String idempotencyKey = "buyer-checkout-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, skuId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());

        CheckoutResponse first = objectMapper.readValue(mockMvc.perform(
                post("/api/buyers/{buyerRef}/cart/checkout", buyerRef).header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, skuId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        CheckoutResponse replay = objectMapper.readValue(mockMvc.perform(
                post("/api/buyers/{buyerRef}/cart/checkout", buyerRef).header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(replay.checkoutId()).isEqualTo(first.checkoutId());
        assertThat(replay.orders()).extracting(OrderResponse::orderId)
                .containsExactlyElementsOf(first.orders().stream().map(OrderResponse::orderId).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE checkout_id = ?",
                Integer.class, first.checkoutId())).isEqualTo(1);

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, skuId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE order_id = ?",
                Integer.class, first.orders().getFirst().orderId())).isZero();
        assertThat(inventory.find(sellerId, skuId).reserved()).isEqualTo(1);
        assertThat(inventory.find(sellerId, skuId).available()).isEqualTo(3);
    }

    @Test
    void buyerWideCheckoutRollsBackAllSellersWhenLaterSellerIsOutOfStock() throws Exception {
        UUID stockedSeller = sellers.createSeller().sellerId();
        UUID emptySeller = sellers.createSeller().sellerId();
        ProductResponse stockedProduct = products.createProduct(stockedSeller, new CreateProductRequest("stocked", null));
        ProductResponse emptyProduct = products.createProduct(emptySeller, new CreateProductRequest("empty", null));
        String firstSku = "rollback-shared-" + UUID.randomUUID();
        String secondSku = "rollback-scarce-" + UUID.randomUUID();
        products.registerSku(stockedSeller, stockedProduct.productId(), new RegisterSellerSkuRequest(firstSku, 100, 1));
        products.registerSku(emptySeller, emptyProduct.productId(), new RegisterSellerSkuRequest(secondSku, 200, 0));
        String buyerRef = "multi-rollback-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, stockedSeller, firstSku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, emptySeller, secondSku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "multi-rollback-" + UUID.randomUUID()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyer_checkout WHERE buyer_ref = ?", Integer.class, buyerRef)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order WHERE buyer_ref = ?", Integer.class, buyerRef)).isZero();
        assertThat(inventory.find(stockedSeller, firstSku).reserved()).isZero();
        assertThat(inventory.find(stockedSeller, firstSku).available()).isEqualTo(1);
        assertThat(inventory.find(emptySeller, secondSku).reserved()).isZero();
        assertThat(objectMapper.readTree(mockMvc.perform(get("/api/buyers/{buyerRef}/cart", buyerRef))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("items").size()).isEqualTo(2);
    }

    @Test
    void cartCancellationIsIdempotentAndReleasesEverySkuOnce() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("cancel-cart", null));
        String firstSku = "cancel-a-" + UUID.randomUUID();
        String secondSku = "cancel-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 4));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 3));
        String buyerRef = "cancel-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", "cancel-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();
        CheckoutResponse pending = objectMapper.readValue(mockMvc.perform(
                post("/api/checkouts/{checkoutId}/payment", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        assertThat(pending.paymentAttemptId()).isNotNull();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/checkouts/{checkoutId}/cancel", checkout.checkoutId())
                            .header("Idempotency-Key", "cart-cancel-" + checkout.checkoutId()))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .contains("\"state\":\"CANCELLED\"", "\"paymentState\":\"VOIDED\""));
        }

        CheckoutResponse cancelled = objectMapper.readValue(mockMvc.perform(
                get("/api/checkouts/{checkoutId}", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(cancelled.state().name()).isEqualTo("CANCELLED");
        assertThat(cancelled.paymentAttemptId()).isEqualTo(pending.paymentAttemptId());
        assertThat(cancelled.paymentState()).isEqualTo(PaymentState.VOIDED);
        assertThat(inventory.findReservations(order.orderId()))
                .extracting(ReservationResponse::status)
                .containsExactly(ReservationStatus.RELEASED, ReservationStatus.RELEASED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE checkout_id = ?",
                Integer.class, checkout.checkoutId())).isEqualTo(1);
        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.CANCELLED);
        assertInventoryRestored(sellerId, firstSku, 4);
        assertInventoryRestored(sellerId, secondSku, 3);
    }

    @Test
    void cartCheckoutExpiryReleasesEverySkuReservation() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("expiry-cart", null));
        String firstSku = "expiry-a-" + UUID.randomUUID();
        String secondSku = "expiry-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 4));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 3));
        String buyerRef = "expiry-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", "expiry-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();

        clock.advance(Duration.ofMinutes(11));
        assertThat(checkoutLifecycle.expireDueCheckouts()).isGreaterThanOrEqualTo(1);

        CheckoutResponse expiredCheckout = checkoutLifecycle.find(checkout.checkoutId());
        assertThat(expiredCheckout.state().name()).isEqualTo("EXPIRED");
        assertThat(expiredCheckout.paymentAttemptId()).isNull();
        assertThat(expiredCheckout.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        OrderResponse expired = orders.find(order.orderId());
        assertThat(expired.state()).isEqualTo(OrderState.EXPIRED);
        assertThat(expired.inventoryState()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventory.findReservations(order.orderId()))
                .extracting(ReservationResponse::status)
                .containsExactly(ReservationStatus.RELEASED, ReservationStatus.RELEASED);
        assertInventoryRestored(sellerId, firstSku, 4);
        assertInventoryRestored(sellerId, secondSku, 3);
    }

    @Test
    void terminalCartOrderCanBeReorderedWithCurrentCatalogAndIdempotentCartMerge() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("reorder-product", null));
        String firstSku = "reorder-a-" + UUID.randomUUID();
        String secondSku = "reorder-b-" + UUID.randomUUID();
        String existingSku = "existing-cart-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 8));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 6));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(existingSku, 300, 4));
        String buyerRef = "reorder-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", "source-order-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse sourceOrder = checkout.orders().getFirst();
        assertThat(checkoutLifecycle.cancel(checkout.checkoutId(), "reorder-cancel-" + checkout.checkoutId())
                .state().name()).isEqualTo("CANCELLED");
        products.updateSkuPrice(sellerId, product.productId(), firstSku, 150);

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, existingSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());

        String reorderKey = "reorder-" + UUID.randomUUID();
        mockMvc.perform(post("/api/orders/{orderId}/reorder", sourceOrder.orderId())
                        .header("Idempotency-Key", reorderKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmExistingCartMerge":false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CONFLICT\""));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_reorder_request WHERE idempotency_key = ?",
                Integer.class, reorderKey)).isZero();

        String reorderJson = mockMvc.perform(post("/api/orders/{orderId}/reorder", sourceOrder.orderId())
                        .header("Idempotency-Key", reorderKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmExistingCartMerge":true}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CartResponse reordered = objectMapper.readValue(reorderJson, CartResponse.class);
        assertThat(reordered.items()).extracting(item -> item.skuId())
                .containsExactlyInAnyOrder(firstSku, secondSku, existingSku);
        var mergedLine = reordered.items().stream()
                .filter(item -> item.skuId().equals(firstSku))
                .findFirst()
                .orElseThrow();
        assertThat(mergedLine.quantity()).isEqualTo(5);
        assertThat(mergedLine.priceWon()).isEqualTo(150);
        assertThat(orders.find(sourceOrder.orderId()).items().get(0).unitPriceWon()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_reorder_request WHERE idempotency_key = ?",
                Integer.class, reorderKey)).isEqualTo(1);

        String replayJson = mockMvc.perform(post("/api/orders/{orderId}/reorder", sourceOrder.orderId())
                        .header("Idempotency-Key", reorderKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmExistingCartMerge":true}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CartResponse replay = objectMapper.readValue(replayJson, CartResponse.class);
        assertThat(replay.items().stream().filter(item -> item.skuId().equals(firstSku))
                .findFirst().orElseThrow().quantity()).isEqualTo(5);
        assertThat(inventory.find(sellerId, firstSku).reserved()).isZero();
        assertThat(inventory.find(sellerId, secondSku).reserved()).isZero();
        assertConserved(inventory.find(sellerId, firstSku));
        assertConserved(inventory.find(sellerId, secondSku));
    }

    @Test
    void reorderWithOneUnavailableSkuLeavesCartAndIdempotencyRecordUnchanged() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("reorder-rollback", null));
        String availableSku = "reorder-available-" + UUID.randomUUID();
        String scarceSku = "reorder-scarce-" + UUID.randomUUID();
        String existingSku = "reorder-existing-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(availableSku, 100, 2));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(scarceSku, 200, 1));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(existingSku, 300, 2));
        String buyerRef = "reorder-rollback-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, availableSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}", buyerRef, sellerId, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String sourceJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", "reorder-source-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse sourceCheckout = objectMapper.readValue(sourceJson, CheckoutResponse.class);
        OrderResponse sourceOrder = sourceCheckout.orders().getFirst();
        checkoutLifecycle.cancel(sourceCheckout.checkoutId(),
                "rollback-reorder-cancel-" + sourceCheckout.checkoutId());

        String competitor = "reorder-competitor-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        competitor, sellerId, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String competitorJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        competitor)
                        .header("Idempotency-Key", "reorder-competitor-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse competitorCheckout = objectMapper.readValue(competitorJson, CheckoutResponse.class);
        OrderResponse competitorOrder = competitorCheckout.orders().getFirst();
        CheckoutResponse paidCompetitor = completePayPalCheckout(competitorCheckout.checkoutId());
        assertThat(paidCompetitor.state().name()).isEqualTo("PAID");

        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, existingSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        String reorderKey = "reorder-rollback-" + UUID.randomUUID();
        mockMvc.perform(post("/api/orders/{orderId}/reorder", sourceOrder.orderId())
                        .header("Idempotency-Key", reorderKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmExistingCartMerge":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"INSUFFICIENT_STOCK\""));

        CartResponse unchanged = objectMapper.readValue(mockMvc.perform(
                        get("/api/buyers/{buyerRef}/cart", buyerRef))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), CartResponse.class);
        assertThat(unchanged.items()).extracting(item -> item.skuId()).containsExactly(existingSku);
        assertThat(unchanged.items().getFirst().quantity()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_reorder_request WHERE idempotency_key = ?",
                Integer.class, reorderKey)).isZero();
        assertThat(inventory.find(sellerId, availableSku).available()).isEqualTo(2);
        assertThat(inventory.find(sellerId, availableSku).reserved()).isZero();
    }

    @Test
    void paidCartOrderCanBeFulfilledInOrderAndBuyerCanReadProgress() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "fulfillment-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, 1);
        String buyerRef = "fulfillment-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "fulfillment-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start",
                        sellerId, order.orderId())).andExpect(status().isConflict());
        CheckoutResponse paymentPending = objectMapper.readValue(mockMvc.perform(
                        post("/api/checkouts/{checkoutId}/payment", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        mockMvc.perform(get("/api/checkouts/{checkoutId}/payment/return", paymentPending.checkoutId())
                        .param("token", approvalToken(paymentPending.paymentApprovalUrl())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start",
                        UUID.randomUUID(), order.orderId())).andExpect(status().isConflict());
        OrderFulfillmentResponse started = objectMapper.readValue(mockMvc.perform(
                        post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start", sellerId, order.orderId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), OrderFulfillmentResponse.class);
        assertThat(started.status()).isEqualTo(FulfillmentStatus.IN_PROGRESS);
        UUID orderItemId = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id=?", UUID.class, order.orderId());
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/shipments", sellerId, order.orderId())
                        .header("Idempotency-Key", "buyer-flow-shipment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":\"" + orderItemId + "\",\"quantity\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        String shipmentsJson = mockMvc.perform(get("/api/buyers/{buyerRef}/orders/{orderId}/shipments",
                        buyerRef, order.orderId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        OrderShipmentResponse[] shipments = objectMapper.readValue(shipmentsJson, OrderShipmentResponse[].class);
        assertThat(shipments).hasSize(1);
        UUID shipmentId = shipments[0].shipmentId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        sellerId, order.orderId(), shipmentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        OrderFulfillmentResponse delivered = objectMapper.readValue(mockMvc.perform(
                        get("/api/orders/{orderId}/fulfillment", order.orderId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), OrderFulfillmentResponse.class);
        assertThat(delivered.status()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.PAID);
    }

    @Test
    void fulfillmentInsertFailureRollsBackCapturedOrderAndInventoryThenReconciles() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "fulfillment-retry-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 150, 2);
        String buyerRef = "fulfillment-retry-buyer-" + UUID.randomUUID();
        mockMvc.perform(put("/api/buyers/{buyerRef}/cart/items/{sellerId}/{skuId}",
                        buyerRef, sellerId, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyerRef}/cart/checkout",
                        buyerRef)
                        .header("Idempotency-Key", "fulfillment-retry-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = objectMapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();
        CheckoutResponse pending = objectMapper.readValue(mockMvc.perform(
                        post("/api/checkouts/{checkoutId}/payment", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        UUID attemptId = pending.paymentAttemptId();
        assertThat(attemptId).isNotNull();
        String token = pending.paymentApprovalUrl()
                .substring(pending.paymentApprovalUrl().lastIndexOf('/') + 1);
        int captureRequestsBeforeReturn = captureRequestCount();
        assertThat(attemptId).isNotNull();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_fulfillment_insert() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated fulfillment insert failure';
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_fulfillment_insert
                BEFORE INSERT ON order_fulfillment
                FOR EACH ROW EXECUTE FUNCTION fail_fulfillment_insert()
                """);
        try {
        mockMvc.perform(get("/api/checkouts/{checkoutId}/payment/return", checkout.checkoutId())
                        .param("token", token))
                .andExpect(status().is5xxServerError());
        assertThat(captureRequestCount()).isEqualTo(captureRequestsBeforeReturn + 1);
            OrderResponse unchanged = orders.find(order.orderId());
            assertThat(unchanged.state()).isEqualTo(OrderState.PAYMENT_PENDING);
            assertThat(unchanged.paymentState()).isEqualTo(PaymentState.CAPTURED);
            assertThat(unchanged.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
            InventoryPoolResponse unchangedStock = inventory.find(sellerId, skuId);
            assertThat(unchangedStock.available()).isEqualTo(1);
            assertThat(unchangedStock.reserved()).isEqualTo(1);
            assertThat(unchangedStock.claimed()).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM settlement_journal WHERE source_attempt_id = ?",
                    Integer.class, attemptId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                    Integer.class, order.orderId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_fulfillment_insert ON order_fulfillment");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_fulfillment_insert()");
        }

        CheckoutResponse recoveredCheckout = checkoutLifecycle.reconcile(checkout.checkoutId(),
                "downstream-retry-" + order.orderId());
        OrderResponse recovered = recoveredCheckout.orders().getFirst();
        assertThat(captureRequestCount()).isEqualTo(captureRequestsBeforeReturn + 1);
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(inventory.find(sellerId, skuId).claimed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
        assertThat(orderFulfillments.find(order.orderId()).status()).isEqualTo(FulfillmentStatus.UNFULFILLED);
        assertThat(checkoutLifecycle.reconcile(checkout.checkoutId(),
                "downstream-retry-" + order.orderId())).isEqualTo(recoveredCheckout);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, attemptId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
    }

    @Test
    void sellerCanRegisterProductAndSkuThroughCatalogApi() throws Exception {
        String sellerJson = mockMvc.perform(post("/api/sellers"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        SellerResponse seller = objectMapper.readValue(sellerJson, SellerResponse.class);
        UUID sellerId = seller.sellerId();

        String productJson = mockMvc.perform(post("/api/sellers/{sellerId}/products", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Seller API Product","description":"A local catalog fixture"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        ProductResponse product = objectMapper.readValue(productJson, ProductResponse.class);

        mockMvc.perform(post("/api/sellers/{sellerId}/products/{productId}/skus",
                        sellerId, product.productId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":"seller-api-sku","priceWon":100,"initialStock":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(sellerId.toString(), product.productId().toString(), "seller-api-sku"));
        mockMvc.perform(put("/api/sellers/{sellerId}/products/{productId}/skus/{skuId}/price",
                        sellerId, product.productId(), "seller-api-sku")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priceWon":125}
                                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"priceWon\":125"));

        mockMvc.perform(get("/api/products/{productId}", product.productId()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(product.productId().toString(), "seller-api-sku", "\"priceWon\":125"));
        mockMvc.perform(get("/api/sellers/{sellerId}/products", sellerId))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Seller API Product", "seller-api-sku"));
        mockMvc.perform(put("/api/sellers/{sellerId}/skus/{skuId}/admission-policy", sellerId, "seller-api-sku")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxActiveCheckouts":2}
                                """))
                .andExpect(status().isOk());

        InventoryPoolResponse stock = inventory.find(sellerId, "seller-api-sku");
        assertThat(stock.initialCapacity()).isEqualTo(5);
    }

    private CampaignResponse openCampaign(int initialStock, int maxActiveCheckouts, Duration ticketTtl) {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "sku-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, initialStock);
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, skuId, maxActiveCheckouts));
        return openCampaign(sellerId, skuId, ticketTtl);
    }

    private CampaignResponse openCampaign(UUID sellerId, String skuId, Duration ticketTtl) {
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(sellerId, skuId, ticketTtl));
        return campaigns.open(campaign.campaignId());
    }
    private ProductResponse registerProductSku(UUID sellerId, String skuId, long priceWon, int initialStock) {
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("Product " + skuId, null));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, priceWon, initialStock));
        return product;
    }

    private QueueTicketResponse join(CampaignResponse campaign, String buyerRef, String key) {
        return admission.join(new JoinQueueRequest(campaign.campaignId(), campaign.skuId(), buyerRef, key));
    }

    private static void assertUuidV7(UUID id) {
        assertThat(id).isNotNull();
        assertThat(id.version()).isEqualTo(7);
    }

    private static void assertConserved(InventoryPoolResponse stock) {
        assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(stock.initialCapacity());
    }
    private void assertInventoryRestored(UUID sellerId, String skuId, int initialStock) {
        InventoryPoolResponse stock = inventory.find(sellerId, skuId);
        assertThat(stock.available()).isEqualTo(initialStock);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
        assertConserved(stock);
    }

    @Test
    void duplicateVerifiedCaptureWebhookDoesNotDuplicateLedgerOrInventoryEffects() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "webhook-buyer", "webhook-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        OrderResponse awaitingApproval = orders.submitPayment(order.orderId());
        String providerOrderId = awaitingApproval.paymentApprovalUrl()
                .substring(awaitingApproval.paymentApprovalUrl().lastIndexOf('/') + 1);
        OrderResponse paid = orders.completePaymentApproval(order.orderId(), providerOrderId);
        PaymentResponse captured = payments.find(order.orderId());

        String eventId = "WH-" + UUID.randomUUID();
        String rawBody = "{\"id\":\"" + eventId
                + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\""
                + captured.providerCaptureId() + "\",\"status\":\"COMPLETED\"}}";
        PaymentWebhookSignature signature = new PaymentWebhookSignature(
                "SHA256withRSA", "https://local.test/cert", "tx-webhook-" + eventId,
                "signature-local", "2026-11-09T00:00:00Z");
        paypalWebhooks.handle(signature, rawBody);
        paypalWebhooks.handle(signature, rawBody);

        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Long.class, paid.paymentAttemptId())).isEqualTo(1L);
        assertThat(inventory.find(campaign.sellerId(), campaign.skuId()).claimed()).isEqualTo(1);
    }
    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-11-02T00:00:00Z"));

        void set(Instant instant) { current.set(instant); }
        void advance(Duration duration) { current.updateAndGet(instant -> instant.plus(duration)); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
