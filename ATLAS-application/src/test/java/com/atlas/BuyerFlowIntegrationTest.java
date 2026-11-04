package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.service.impl.AdmissionServiceImpl;
import com.atlas.cart.dto.response.CartResponse;
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
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderService;
import com.atlas.order.service.impl.OrderServiceImpl;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.fake.FakePsp;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
class BuyerFlowIntegrationTest {

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

    @Autowired private AdmissionServiceImpl admissionServiceImpl;
    @Autowired private AdmissionService admission;
    @Autowired private CampaignService campaigns;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private OrderService orders;
    @Autowired private OrderFulfillmentService orderFulfillments;
    @Autowired private FakePsp fakePsp;
    @Autowired private AdjustableClock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mockMvc;
@Autowired private JdbcTemplate jdbc;

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
                            .contains("\"code\":\"INVALID_REQUEST\"", "\"detail\":\"Request validation failed\"");
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
        mockMvc.perform(get("/api/sellers/{sellerId}/carts/{buyerRef}", UUID.randomUUID(), "missing-buyer"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CART_NOT_FOUND\""));
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
        assertThat(orders.submitPayment(order.orderId()).state()).isEqualTo(OrderState.PAID);

        OrderFulfillmentResponse fulfillment = objectMapper.readValue(
                mockMvc.perform(get("/api/orders/{orderId}/fulfillment", order.orderId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                OrderFulfillmentResponse.class);
        assertThat(fulfillment.status()).isEqualTo(FulfillmentStatus.UNFULFILLED);

        OrderResponse reconciled = orders.reconcilePayment(order.orderId());
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
    void concurrentCampaignsShareGlobalSellerSkuFifoAndCapacity() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "shared-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 100, 3);
        admission.configureSellerSkuPolicy(sellerId, skuId, 1);
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
        admission.configureSellerSkuPolicy(anotherSellerId, skuId, 1);
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
        assertThat(orders.submitPayment(first.orderId()).state()).isEqualTo(OrderState.PAID);
    }

    @Test
    void unknownAuthorizationKeepsHoldThenReconcilesSameAttempt() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-recovery", "recovery-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        OrderResponse unknown = orders.submitPayment(order.orderId());
        assertThat(unknown.state()).isEqualTo(OrderState.PAYMENT_UNKNOWN);
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(unknown.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(unknown.ticketState()).isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);

        OrderResponse recovered = orders.reconcilePayment(order.orderId());
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
    }

    @Test
    void cancellationVoidsRecoveredAuthorizationInsteadOfCapturingIt() {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-cancel", "cancel-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.PENDING, FakePsp.Result.AUTHORIZED);

        OrderResponse pending = orders.submitPayment(order.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        OrderResponse cancelled = orders.cancel(order.orderId());

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
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        OrderResponse unknown = orders.submitPayment(order.orderId());
        mockMvc.perform(post("/api/orders/{orderId}/reconcile", order.orderId()))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"state\":\"PAID\"", "\"paymentState\":\"CAPTURED\"",
                                "\"paymentAttemptId\":\"" + unknown.paymentAttemptId() + "\""));

        OrderResponse recovered = orders.find(order.orderId());
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        InventoryPoolResponse stock = inventory.find(campaign.sellerId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);

        mockMvc.perform(post("/api/orders/{orderId}/reconcile", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"ORDER_NOT_FOUND\""));
    }

    @Test
    void cancellationEndpointVoidsPendingAuthorizationAndReleasesInventory() throws Exception {
        CampaignResponse campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketResponse ticket = join(campaign, "buyer-api-cancel", "api-cancel-key");
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.PENDING, FakePsp.Result.AUTHORIZED);

        orders.submitPayment(order.orderId());
        mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId()))
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

        mockMvc.perform(post("/api/orders/{orderId}/cancel", UUID.randomUUID()))
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
        assertThat(orderServiceImpl.expireDueCheckouts()).isEqualTo(1);

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
    void buyerCannotStartAnotherPaymentWhileAnEarlierCampaignAttemptIsUnresolved() {
        CampaignResponse campaign = openCampaign(2, 2, Duration.ofMinutes(5));
        QueueTicketResponse firstTicket = join(campaign, "same-buyer", "first-key");
        QueueTicketResponse secondTicket = join(campaign, "same-buyer", "second-key");
        OrderResponse first = orders.startCheckout(campaign.campaignId(), firstTicket.ticketId());
        OrderResponse second = orders.startCheckout(campaign.campaignId(), secondTicket.ticketId());
        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        OrderResponse unknown = orders.submitPayment(first.orderId());
        assertThat(unknown.state()).isEqualTo(OrderState.PAYMENT_UNKNOWN);
        assertThatThrownBy(() -> orders.submitPayment(second.orderId()))
                .isInstanceOfSatisfying(OrderException.class, exception ->
                        assertThat(exception.code()).isEqualTo(OrderException.Code.CONFLICT));

        assertThat(orders.reconcilePayment(first.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(orders.submitPayment(second.orderId()).state()).isEqualTo(OrderState.PAID);
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
    void sellerCartCheckoutSnapshotsCurrentPriceAndReservesAllSkuLines() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        ProductResponse product = products.createProduct(sellerId, new CreateProductRequest("cart-product", null));
        String firstSku = "cart-a-" + UUID.randomUUID();
        String secondSku = "cart-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 100, 4));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 200, 3));
        String buyerRef = "cart-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());

        products.updateSkuPrice(sellerId, product.productId(), firstSku, 125);
        String idempotencyKey = "cart-checkout-" + UUID.randomUUID();
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse order = objectMapper.readValue(checkoutJson, OrderResponse.class);

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

        mockMvc.perform(get("/api/sellers/{sellerId}/carts/{buyerRef}", sellerId, buyerRef))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"items\":[]"));
        mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout", sellerId, buyerRef)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(order.orderId().toString()));
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

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, availableSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout", sellerId, buyerRef)
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
        mockMvc.perform(get("/api/sellers/{sellerId}/carts/{buyerRef}", sellerId, buyerRef))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(availableSku, scarceSku));
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

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "cancel-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse order = objectMapper.readValue(checkoutJson, OrderResponse.class);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId()))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                            .contains("\"state\":\"CANCELLED\"", "\"paymentState\":\"NOT_STARTED\""));
        }

        OrderResponse cancelled = orders.find(order.orderId());
        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(cancelled.paymentAttemptId()).isNull();
        assertThat(cancelled.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        assertThat(inventory.findReservations(order.orderId()))
                .extracting(ReservationResponse::status)
                .containsExactly(ReservationStatus.RELEASED, ReservationStatus.RELEASED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE order_id = ?",
                Integer.class, order.orderId())).isZero();
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

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "expiry-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse order = objectMapper.readValue(checkoutJson, OrderResponse.class);

        clock.advance(Duration.ofMinutes(11));
        orderServiceImpl.expireDueCheckouts();

        OrderResponse expired = orders.find(order.orderId());
        assertThat(expired.state()).isEqualTo(OrderState.EXPIRED);
        assertThat(expired.inventoryState()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(inventory.findReservations(order.orderId()))
                .extracting(ReservationResponse::status)
                .containsExactly(ReservationStatus.RELEASED, ReservationStatus.RELEASED);
        assertThat(expired.paymentAttemptId()).isNull();
        assertThat(expired.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
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

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, secondSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "source-order-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse sourceOrder = objectMapper.readValue(checkoutJson, OrderResponse.class);
        assertThat(orders.cancel(sourceOrder.orderId()).state()).isEqualTo(OrderState.CANCELLED);
        products.updateSkuPrice(sellerId, product.productId(), firstSku, 150);

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, firstSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, existingSku)
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

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}", sellerId, buyerRef, availableSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}", sellerId, buyerRef, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String sourceJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "reorder-source-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse sourceOrder = objectMapper.readValue(sourceJson, OrderResponse.class);
        orders.cancel(sourceOrder.orderId());

        String competitor = "reorder-competitor-" + UUID.randomUUID();
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, competitor, scarceSku)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String competitorJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, competitor)
                        .header("Idempotency-Key", "reorder-competitor-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse competitorOrder = objectMapper.readValue(competitorJson, OrderResponse.class);
        assertThat(orders.submitPayment(competitorOrder.orderId()).state()).isEqualTo(OrderState.PAID);

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, existingSku)
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
                        get("/api/sellers/{sellerId}/carts/{buyerRef}", sellerId, buyerRef))
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
        registerProductSku(sellerId, skuId, 100, 2);
        String buyerRef = "fulfillment-buyer-" + UUID.randomUUID();

        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "fulfillment-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse order = objectMapper.readValue(checkoutJson, OrderResponse.class);
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CONFLICT\""));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isZero();
        assertThat(orders.submitPayment(order.orderId()).state()).isEqualTo(OrderState.PAID);

        OrderFulfillmentResponse initial = objectMapper.readValue(
                mockMvc.perform(get("/api/orders/{orderId}/fulfillment", order.orderId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                OrderFulfillmentResponse.class);
        assertThat(initial.status()).isEqualTo(FulfillmentStatus.UNFULFILLED);
        assertThat(initial.createdAt()).isNotNull();

        UUID otherSellerId = sellers.createSeller().sellerId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment",
                        otherSellerId, order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CONFLICT\""));

        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHIPPED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"CONFLICT\""));

        CyclicBarrier transitionStart = new CyclicBarrier(3);
        var fulfillmentWorkers = Executors.newFixedThreadPool(2);
        Future<OrderFulfillmentResponse> firstTransition = fulfillmentWorkers.submit(() -> {
            transitionStart.await(10, TimeUnit.SECONDS);
            return orderFulfillments.updateStatus(sellerId, order.orderId(), FulfillmentStatus.IN_PROGRESS);
        });
        Future<OrderFulfillmentResponse> repeatedTransition = fulfillmentWorkers.submit(() -> {
            transitionStart.await(10, TimeUnit.SECONDS);
            return orderFulfillments.updateStatus(sellerId, order.orderId(), FulfillmentStatus.IN_PROGRESS);
        });
        transitionStart.await(10, TimeUnit.SECONDS);
        OrderFulfillmentResponse inProgress;
        OrderFulfillmentResponse concurrentRepeat;
        try {
            inProgress = firstTransition.get(10, TimeUnit.SECONDS);
            concurrentRepeat = repeatedTransition.get(10, TimeUnit.SECONDS);
        } finally {
            fulfillmentWorkers.shutdownNow();
        }
        assertThat(inProgress.status()).isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(inProgress.inProgressAt()).isNotNull();
        assertThat(concurrentRepeat.inProgressAt()).isEqualTo(inProgress.inProgressAt());

        String repeatedJson = mockMvc.perform(
                        put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"IN_PROGRESS"}
                                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderFulfillmentResponse repeated = objectMapper.readValue(repeatedJson, OrderFulfillmentResponse.class);
        assertThat(repeated.inProgressAt()).isEqualTo(inProgress.inProgressAt());

        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHIPPED"}
                                """))
                .andExpect(status().isOk());
        String deliveredJson = mockMvc.perform(
                        put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment", sellerId, order.orderId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"DELIVERED"}
                                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderFulfillmentResponse delivered = objectMapper.readValue(deliveredJson, OrderFulfillmentResponse.class);
        assertThat(delivered.status()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isNotNull();

        OrderFulfillmentResponse buyerView = objectMapper.readValue(
                mockMvc.perform(get("/api/orders/{orderId}/fulfillment", order.orderId()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                OrderFulfillmentResponse.class);
        assertThat(buyerView.status()).isEqualTo(FulfillmentStatus.DELIVERED);
        assertThat(orders.find(order.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(inventory.find(sellerId, skuId).claimed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
    }

    @Test
    void fulfillmentInsertFailureRollsBackCapturedOrderAndInventoryThenReconciles() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String skuId = "fulfillment-retry-" + UUID.randomUUID();
        registerProductSku(sellerId, skuId, 150, 2);
        String buyerRef = "fulfillment-retry-buyer-" + UUID.randomUUID();
        mockMvc.perform(put("/api/sellers/{sellerId}/carts/{buyerRef}/items/{skuId}",
                        sellerId, buyerRef, skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isOk());
        String checkoutJson = mockMvc.perform(post("/api/sellers/{sellerId}/carts/{buyerRef}/checkout",
                        sellerId, buyerRef)
                        .header("Idempotency-Key", "fulfillment-retry-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        OrderResponse order = objectMapper.readValue(checkoutJson, OrderResponse.class);

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
            assertThatThrownBy(() -> orders.submitPayment(order.orderId())).isInstanceOf(RuntimeException.class);
            OrderResponse unchanged = orders.find(order.orderId());
            assertThat(unchanged.state()).isEqualTo(OrderState.PAYMENT_SUBMITTED);
            assertThat(unchanged.paymentState()).isEqualTo(PaymentState.CAPTURED);
            assertThat(unchanged.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                    Integer.class, order.orderId())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_fulfillment_insert ON order_fulfillment");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_fulfillment_insert()");
        }

        OrderResponse recovered = orders.reconcilePayment(order.orderId());
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.inventoryState()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(inventory.find(sellerId, skuId).claimed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_fulfillment WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
        assertThat(orderFulfillments.find(order.orderId()).status()).isEqualTo(FulfillmentStatus.UNFULFILLED);
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
        admission.configureSellerSkuPolicy(sellerId, skuId, maxActiveCheckouts);
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
