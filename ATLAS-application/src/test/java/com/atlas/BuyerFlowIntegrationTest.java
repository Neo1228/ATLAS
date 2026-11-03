package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.exception.AdmissionException;
import com.atlas.admission.dto.request.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.admission.service.impl.AdmissionServiceImpl;
import com.atlas.campaign.service.CampaignService;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.request.RegisterSellerSkuRequest;
import com.atlas.campaign.service.SellerService;
import com.atlas.campaign.service.SellerSkuService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.inventory.dto.response.ReservationResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.service.OrderService;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.service.impl.OrderServiceImpl;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.persistence.state.PaymentState;
import com.atlas.payment.provider.fake.FakePsp;
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
    @Autowired private CampaignService campaigns;
    @Autowired private SellerService sellers;
    @Autowired private SellerSkuService sellerSkus;
    @Autowired private AdmissionService admission;
    @Autowired private InventoryService inventory;
    @Autowired private OrderService orders;
    @Autowired private FakePsp fakePsp;
    @Autowired private AdjustableClock clock;
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
    }

    @Test
    void FIFOAdmissionAndIdempotencyReleaseWindowAtInitialPaymentSubmission() {
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
        UUID sellerId = sellers.create().sellerId();
        String skuId = "shared-" + UUID.randomUUID();
        sellerSkus.register(sellerId, new RegisterSellerSkuRequest(skuId, 3, 1));
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

        UUID anotherSellerId = sellers.create().sellerId();
        sellerSkus.register(anotherSellerId, new RegisterSellerSkuRequest(skuId, 1, 1));
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
    void sellerRegistrationAndSellerSkuApiCreateInitialSharedInventory() throws Exception {
        mockMvc.perform(post("/api/sellers"))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"sellerId\":\""));

        UUID sellerId = sellers.create().sellerId();
        mockMvc.perform(post("/api/sellers/{sellerId}/skus", sellerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":"seller-api-sku","initialStock":5,"maxActiveCheckouts":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(sellerId.toString(), "seller-api-sku"));

        InventoryPoolResponse stock = inventory.find(sellerId, "seller-api-sku");
        assertThat(stock.initialCapacity()).isEqualTo(5);
    }

    private CampaignResponse openCampaign(int initialStock, int maxActiveCheckouts, Duration ticketTtl) {
        UUID sellerId = sellers.create().sellerId();
        String skuId = "sku-" + UUID.randomUUID();
        sellerSkus.register(sellerId, new RegisterSellerSkuRequest(skuId, initialStock, maxActiveCheckouts));
        return openCampaign(sellerId, skuId, ticketTtl);
    }

    private CampaignResponse openCampaign(UUID sellerId, String skuId, Duration ticketTtl) {
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(sellerId, skuId, ticketTtl));
        return campaigns.open(campaign.campaignId());
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
