package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.AdmissionApi;
import com.atlas.admission.CheckoutSlotReleaseReason;
import com.atlas.admission.IdempotencyPayloadMismatchException;
import com.atlas.admission.JoinQueueCommand;
import com.atlas.admission.QueueTicketView;
import com.atlas.admission.TicketStatus;
import com.atlas.admission.internal.AdmissionExpiryScheduler;
import com.atlas.campaign.CampaignApi;
import com.atlas.campaign.CampaignView;
import com.atlas.campaign.CreateCampaignCommand;
import com.atlas.inventory.InventoryApi;
import com.atlas.inventory.InventoryView;
import com.atlas.inventory.ReservationStatus;
import com.atlas.order.OrderApi;
import com.atlas.order.OrderConflictException;
import com.atlas.order.OrderState;
import com.atlas.order.OrderView;
import com.atlas.order.internal.CheckoutExpiryScheduler;
import com.atlas.payment.PaymentState;
import com.atlas.payment.internal.FakePsp;
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

    @Autowired private CheckoutExpiryScheduler checkoutExpiryScheduler;

    @Autowired private AdmissionExpiryScheduler admissionExpiryScheduler;
    @Autowired private CampaignApi campaigns;
    @Autowired private AdmissionApi admission;
    @Autowired private InventoryApi inventory;
    @Autowired private OrderApi orders;
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
        CampaignView campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        QueueTicketView first = join(campaign, "buyer-a", "key-a");
        QueueTicketView second = join(campaign, "buyer-b", "key-b");
        QueueTicketView replay = join(campaign, "buyer-a", "key-a");

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(second.status()).isEqualTo(TicketStatus.WAITING);
        assertThatThrownBy(() -> join(campaign, "different-buyer", "key-a"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);

        OrderView order = orders.startCheckout(campaign.campaignId(), first.ticketId());
        OrderView checkoutReplay = orders.startCheckout(campaign.campaignId(), first.ticketId());
        assertThat(checkoutReplay.orderId()).isEqualTo(order.orderId());
        assertThat(order.state()).isEqualTo(OrderState.RESERVED);
        assertThat(order.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(orders.submitPayment(order.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        InventoryView stock = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
    }

    @Test
    void soldOutCheckoutReturnsFailureWithoutPaymentAndReleasesQueueSlot() {
        CampaignView campaign = openCampaign(1, 2, Duration.ofMinutes(5));
        QueueTicketView firstTicket = join(campaign, "buyer-first", "first-stock-key");
        QueueTicketView secondTicket = join(campaign, "buyer-second", "second-stock-key");
        QueueTicketView thirdTicket = join(campaign, "buyer-third", "third-stock-key");
        OrderView first = orders.startCheckout(campaign.campaignId(), firstTicket.ticketId());

        OrderView soldOut = orders.startCheckout(campaign.campaignId(), secondTicket.ticketId());
        assertThat(soldOut.state()).isEqualTo(OrderState.FAILED);
        assertThat(soldOut.paymentAttemptId()).isNull();
        assertThat(soldOut.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        assertThat(soldOut.ticketState()).isEqualTo(TicketStatus.CHECKOUT_FAILED);
        assertThat(orders.submitPayment(soldOut.orderId()).state()).isEqualTo(OrderState.FAILED);
        assertThat(admission.find(campaign.campaignId(), thirdTicket.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);

        InventoryView held = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(held.available()).isZero();
        assertThat(held.reserved()).isEqualTo(1);
        assertConserved(held);
        assertThat(orders.submitPayment(first.orderId()).state()).isEqualTo(OrderState.PAID);
    }

    @Test
    void unknownAuthorizationKeepsHoldThenReconcilesSameAttempt() {
        CampaignView campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketView ticket = join(campaign, "buyer-recovery", "recovery-key");
        OrderView order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        fakePsp.enqueue(FakePsp.Operation.AUTHORIZATION, FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        OrderView unknown = orders.submitPayment(order.orderId());
        assertThat(unknown.state()).isEqualTo(OrderState.PAYMENT_UNKNOWN);
        assertThat(unknown.paymentState()).isEqualTo(PaymentState.UNKNOWN);
        assertThat(unknown.inventoryState()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(unknown.ticketState()).isEqualTo(TicketStatus.CHECKOUT_SUBMITTED);

        OrderView recovered = orders.reconcilePayment(order.orderId());
        assertThat(recovered.state()).isEqualTo(OrderState.PAID);
        assertThat(recovered.paymentState()).isEqualTo(PaymentState.CAPTURED);
        assertThat(recovered.paymentAttemptId()).isEqualTo(unknown.paymentAttemptId());
        InventoryView stock = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(stock.claimed()).isEqualTo(1);
        assertConserved(stock);
    }

    @Test
    void cancellationVoidsRecoveredAuthorizationInsteadOfCapturingIt() {
        CampaignView campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketView ticket = join(campaign, "buyer-cancel", "cancel-key");
        OrderView order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        fakePsp.enqueue(FakePsp.Operation.AUTHORIZATION, FakePsp.Result.PENDING, FakePsp.Result.AUTHORIZED);

        OrderView pending = orders.submitPayment(order.orderId());
        assertThat(pending.paymentState()).isEqualTo(PaymentState.AUTHORIZATION_PENDING);
        OrderView cancelled = orders.cancel(order.orderId());

        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(cancelled.paymentState()).isEqualTo(PaymentState.VOIDED);
        InventoryView stock = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isZero();
        assertConserved(stock);
    }

    @Test
    void scheduledAdmissionExpiryRemainsInternalAndExpiresDueTickets() {
        CampaignView campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketView ticket = join(campaign, "buyer-queue-expiry", "queue-expiry-key");
        clock.advance(Duration.ofMinutes(6));

        admissionExpiryScheduler.expireDueTickets();
        assertThat(admission.find(campaign.campaignId(), ticket.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
    }

    @Test
    void unsubmittedCheckoutExpiryReleasesHoldAndExpiresTicket() {
        CampaignView campaign = openCampaign(1, 1, Duration.ofMinutes(5));
        QueueTicketView ticket = join(campaign, "buyer-expiry", "expiry-key");
        OrderView order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        clock.advance(Duration.ofMinutes(10));
        assertThat(checkoutExpiryScheduler.expireDueCheckouts()).isEqualTo(1);

        OrderView expired = orders.find(order.orderId());
        assertThat(expired.state()).isEqualTo(OrderState.EXPIRED);
        assertThat(expired.paymentAttemptId()).isNull();
        assertThat(expired.paymentState()).isEqualTo(PaymentState.NOT_STARTED);
        assertThat(expired.inventoryState()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(expired.ticketState()).isEqualTo(TicketStatus.EXPIRED);
        InventoryView stock = inventory.find(campaign.campaignId(), campaign.skuId());
        assertThat(stock.available()).isEqualTo(1);
        assertThat(stock.reserved()).isZero();
        assertConserved(stock);
    }

    @Test
    void buyerCannotStartAnotherPaymentWhileAnEarlierCampaignAttemptIsUnresolved() {
        CampaignView campaign = openCampaign(2, 2, Duration.ofMinutes(5));
        QueueTicketView firstTicket = join(campaign, "same-buyer", "first-key");
        QueueTicketView secondTicket = join(campaign, "same-buyer", "second-key");
        OrderView first = orders.startCheckout(campaign.campaignId(), firstTicket.ticketId());
        OrderView second = orders.startCheckout(campaign.campaignId(), secondTicket.ticketId());
        fakePsp.enqueue(FakePsp.Operation.AUTHORIZATION, FakePsp.Result.UNKNOWN, FakePsp.Result.AUTHORIZED);

        OrderView unknown = orders.submitPayment(first.orderId());
        assertThat(unknown.state()).isEqualTo(OrderState.PAYMENT_UNKNOWN);
        assertThatThrownBy(() -> orders.submitPayment(second.orderId()))
                .isInstanceOf(OrderConflictException.class);

        assertThat(orders.reconcilePayment(first.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(orders.submitPayment(second.orderId()).state()).isEqualTo(OrderState.PAID);
    }

    @Test
    void ticketLookupDoesNotExpireTicketsOrPromoteWaitingBuyers() {
        CampaignView campaign = openCampaign(2, 1, Duration.ofMinutes(5));
        QueueTicketView first = join(campaign, "buyer-read-a", "read-key-a");
        clock.advance(Duration.ofMinutes(4));
        QueueTicketView second = join(campaign, "buyer-read-b", "read-key-b");
        clock.advance(Duration.ofMinutes(2));

        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.WAITING);

        admissionExpiryScheduler.expireDueTickets();
        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status())
                .isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
    }

    @Test
    void releasingAnExpiredTicketPreservesExpiryAndPromotesTheNextLiveTicket() {
        CampaignView campaign = openCampaign(2, 1, Duration.ofMinutes(2));
        QueueTicketView expired = join(campaign, "buyer-expired-release", "expired-release-key");
        clock.advance(Duration.ofMinutes(1));
        QueueTicketView waiting = join(campaign, "buyer-waiting-release", "waiting-release-key");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        admission.releaseCheckoutSlot(campaign.campaignId(), expired.ticketId(), CheckoutSlotReleaseReason.BUYER_CANCELLED);

        assertThat(admission.find(campaign.campaignId(), expired.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), waiting.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
    }

    @Test
    void expiredQueueTicketRemainsAnIdempotentTombstoneAndNewKeyRejoinsAtTail() {
        CampaignView campaign = openCampaign(2, 1, Duration.ofMinutes(2));
        QueueTicketView first = join(campaign, "buyer-reentry", "first-key");
        clock.advance(Duration.ofMinutes(1));
        QueueTicketView second = join(campaign, "buyer-next", "second-key");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        admissionExpiryScheduler.expireDueTickets();

        assertThat(admission.find(campaign.campaignId(), first.ticketId()).status()).isEqualTo(TicketStatus.EXPIRED);
        assertThat(admission.find(campaign.campaignId(), second.ticketId()).status())
                .isEqualTo(TicketStatus.CHECKOUT_ACTIVE);
        assertThat(join(campaign, "buyer-reentry", "first-key").ticketId()).isEqualTo(first.ticketId());
        QueueTicketView reentry = join(campaign, "buyer-reentry", "new-key");
        assertThat(reentry.sequence()).isEqualTo(3);
        assertThat(reentry.status()).isEqualTo(TicketStatus.WAITING);
    }

    private CampaignView openCampaign(int capacity, int maxActiveCheckouts, Duration ticketTtl) {
        CampaignView campaign = campaigns.create(new CreateCampaignCommand(
                "sku-" + UUID.randomUUID(), capacity, ticketTtl, maxActiveCheckouts));
        return campaigns.open(campaign.campaignId());
    }

    private QueueTicketView join(CampaignView campaign, String buyerRef, String key) {
        return admission.join(new JoinQueueCommand(campaign.campaignId(), campaign.skuId(), buyerRef, key));
    }

    private static void assertConserved(InventoryView stock) {
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
