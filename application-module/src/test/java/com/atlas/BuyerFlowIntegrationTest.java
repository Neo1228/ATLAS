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
import com.atlas.inventory.service.InventoryService;
import com.atlas.inventory.dto.response.InventoryResponse;
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
import java.util.UUID;
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
    @Autowired private AdmissionService admission;
    @Autowired private InventoryService inventory;
    @Autowired private OrderService orders;
    @Autowired private FakePsp fakePsp;
    @Autowired private AdjustableClock clock;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void requestValidationAndMissingResourcesUseOneApiErrorContract() throws Exception {
        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":"","inventoryCapacity":0,"queueTicketTtlSeconds":0,"maxActiveCheckouts":0}
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
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);
        assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(orders.submitPayment(order.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        InventoryResponse stock = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
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

        InventoryResponse held = inventory.find(campaign.campaignId(), campaign.skuId());
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
        InventoryResponse stock = inventory.find(campaign.campaignId(), campaign.skuId());
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
        InventoryResponse stock = inventory.find(campaign.campaignId(), campaign.skuId());
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
        InventoryResponse stock = inventory.find(campaign.campaignId(), campaign.skuId());
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

    private CampaignResponse openCampaign(int capacity, int maxActiveCheckouts, Duration ticketTtl) {
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(
                "sku-" + UUID.randomUUID(), capacity, ticketTtl, maxActiveCheckouts));
        return campaigns.open(campaign.campaignId());
    }

    private QueueTicketResponse join(CampaignResponse campaign, String buyerRef, String key) {
        return admission.join(new JoinQueueRequest(campaign.campaignId(), campaign.skuId(), buyerRef, key));
    }

    private static void assertConserved(InventoryResponse stock) {
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
