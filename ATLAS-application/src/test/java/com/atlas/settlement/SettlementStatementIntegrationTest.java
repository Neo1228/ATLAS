package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.service.SettlementService;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class SettlementStatementIntegrationTest {
    private static final Instant PERIOD_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant PERIOD_TO = Instant.parse("2100-01-01T00:00:00Z");

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

    @Autowired private AdmissionService admission;
    @Autowired private CampaignService campaigns;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orders;
    @Autowired private OrderRefundService refunds;
    @Autowired private ProductService products;
    @Autowired private SellerService sellers;
    @Autowired private SettlementService settlement;

    @Test
    void statementUsesSellerAllocationsAndRefundMovementsAcrossStablePages() throws Exception {
        UUID firstSellerId = sellers.createSeller().sellerId();
        UUID secondSellerId = sellers.createSeller().sellerId();
        String firstSkuId = registerSku(firstSellerId, 100, 10);
        String secondSkuId = registerSku(secondSellerId, 300, 10);
        String buyerRef = "statement-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(firstSellerId, buyerRef, firstSkuId, 2);
        carts.setItemQuantity(secondSellerId, buyerRef, secondSkuId, 3);

        CheckoutResponse checkout = checkouts.checkout(buyerRef, "statement-checkout-" + UUID.randomUUID());
        CheckoutResponse paidCheckout = checkoutPayments.submit(checkout.checkoutId());
        assertThat(paidCheckout.orders()).hasSize(2);
        assertThat(paidCheckout.orders()).allSatisfy(order -> assertThat(order.state()).isEqualTo(OrderState.PAID));

        long checkoutGross = jdbc.queryForObject(
                "SELECT gross_amount_won FROM settlement_journal WHERE checkout_id = ?", Long.class,
                checkout.checkoutId());
        var firstSellerCheckout = settlement.getSellerStatement(statementRequest(
                firstSellerId, PERIOD_FROM, PERIOD_TO, 10, null));
        var secondSellerCheckout = settlement.getSellerStatement(statementRequest(
                secondSellerId, PERIOD_FROM, PERIOD_TO, 10, null));
        assertThat(firstSellerCheckout.items()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(SellerSettlementStatementResponse.EntryType.CHECKOUT_ALLOCATION);
            assertThat(entry.signedAmountWon()).isEqualTo(200);
        });
        assertThat(secondSellerCheckout.items()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(SellerSettlementStatementResponse.EntryType.CHECKOUT_ALLOCATION);
            assertThat(entry.signedAmountWon()).isEqualTo(900);
        });
        assertThat(firstSellerCheckout.items().get(0).signedAmountWon()
                + secondSellerCheckout.items().get(0).signedAmountWon()).isEqualTo(checkoutGross);

        String campaignSkuId = registerSku(firstSellerId, 75, 2);
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(firstSellerId, campaignSkuId, 1));
        CampaignResponse campaign = campaigns.create(
                new CreateCampaignRequest(firstSellerId, campaignSkuId, Duration.ofMinutes(5)));
        campaign = campaigns.open(campaign.campaignId());
        String campaignBuyer = "statement-campaign-buyer-" + UUID.randomUUID();
        var ticket = admission.join(new JoinQueueRequest(
                campaign.campaignId(), campaignSkuId, campaignBuyer, "statement-ticket-" + UUID.randomUUID()));
        OrderResponse reservedCampaignOrder = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        OrderResponse paidCampaignOrder = orders.submitPayment(reservedCampaignOrder.orderId());
        assertThat(paidCampaignOrder.state()).isEqualTo(OrderState.PAID);

        UUID campaignOrderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, paidCampaignOrder.orderId());
        UUID refundId = refunds.refund(new OrderRefundRequest(firstSellerId, paidCampaignOrder.orderId(),
                "statement-refund-" + UUID.randomUUID(),
                List.of(new OrderRefundRequest.Item(campaignOrderItemId, 1)))).refundId();
        assertThat(orders.find(paidCampaignOrder.orderId()).state()).isEqualTo(OrderState.PAID);
        var fullPeriod = settlement.getSellerStatement(statementRequest(
                firstSellerId, PERIOD_FROM, PERIOD_TO, 100, null));
        assertThat(fullPeriod.items()).hasSize(3);
        assertThat(fullPeriod.totals().capturedAmountWon()).isEqualTo(BigInteger.valueOf(275));
        assertThat(fullPeriod.totals().refundReversalAmountWon()).isEqualTo(BigInteger.valueOf(-75));
        assertThat(fullPeriod.totals().netLedgerChangeWon()).isEqualTo(BigInteger.valueOf(200));
        assertThat(fullPeriod.totals().capturedAmountWon()
                .add(fullPeriod.totals().refundReversalAmountWon()))
                .isEqualTo(fullPeriod.totals().netLedgerChangeWon());

        var campaignCapture = fullPeriod.items().stream()
                .filter(entry -> entry.type() == SellerSettlementStatementResponse.EntryType.ORDER_CAPTURE)
                .findFirst().orElseThrow();
        assertThat(campaignCapture.orderId()).isEqualTo(paidCampaignOrder.orderId());
        assertThat(campaignCapture.sourceId()).isEqualTo(paidCampaignOrder.paymentAttemptId());
        assertThat(campaignCapture.signedAmountWon()).isEqualTo(75);
        var refundReversal = fullPeriod.items().stream()
                .filter(entry -> entry.type() == SellerSettlementStatementResponse.EntryType.REFUND_REVERSAL)
                .findFirst().orElseThrow();
        assertThat(refundReversal.orderId()).isEqualTo(paidCampaignOrder.orderId());
        assertThat(refundReversal.sourceId()).isEqualTo(refundId);
        assertThat(refundReversal.signedAmountWon()).isEqualTo(-75);

        UUID campaignJournalId = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_attempt_id = ?",
                UUID.class, paidCampaignOrder.paymentAttemptId());
        Instant campaignRecordedAt = jdbc.queryForObject(
                "SELECT recorded_at FROM settlement_journal WHERE journal_id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp("recorded_at").toInstant(), campaignJournalId);
        var inclusivePeriod = settlement.getSellerStatement(statementRequest(
                firstSellerId, campaignRecordedAt, campaignRecordedAt.plusSeconds(1), 100, null));
        var exclusiveUpperBound = settlement.getSellerStatement(statementRequest(
                firstSellerId, campaignRecordedAt.minusSeconds(1), campaignRecordedAt, 100, null));
        assertThat(inclusivePeriod.items()).extracting(SellerSettlementStatementResponse.Entry::journalId)
                .contains(campaignJournalId);
        assertThat(exclusiveUpperBound.items()).extracting(SellerSettlementStatementResponse.Entry::journalId)
                .doesNotContain(campaignJournalId);
        var emptyPeriod = settlement.getSellerStatement(statementRequest(
                firstSellerId, PERIOD_TO, PERIOD_TO.plusSeconds(60), 10, null));
        assertThat(emptyPeriod.items()).isEmpty();
        assertThat(emptyPeriod.totals().capturedAmountWon()).isZero();
        assertThat(emptyPeriod.totals().refundReversalAmountWon()).isZero();
        assertThat(emptyPeriod.totals().netLedgerChangeWon()).isZero();

        List<SellerSettlementStatementResponse.Entry> pagedEntries = new ArrayList<>();
        String cursor = null;
        do {
            var page = settlement.getSellerStatement(statementRequest(
                    firstSellerId, PERIOD_FROM, PERIOD_TO, 1, cursor));
            assertThat(page.totals()).isEqualTo(fullPeriod.totals());
            pagedEntries.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(pagedEntries).containsExactlyElementsOf(fullPeriod.items());

        String firstCursor = settlement.getSellerStatement(statementRequest(
                firstSellerId, PERIOD_FROM, PERIOD_TO, 1, null)).nextCursor();
        assertThat(firstCursor).isNotBlank();
        assertThatThrownBy(() -> settlement.getSellerStatement(statementRequest(
                secondSellerId, PERIOD_FROM, PERIOD_TO, 1, firstCursor)))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SettlementException.Code.INVALID_REQUEST));
        assertThatThrownBy(() -> settlement.getSellerStatement(statementRequest(
                firstSellerId, PERIOD_FROM.plusSeconds(1), PERIOD_TO, 1, firstCursor)))
                .isInstanceOfSatisfying(SettlementException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(SettlementException.Code.INVALID_REQUEST));
        mockMvc.perform(get("/api/sellers/{sellerId}/settlement-statement", secondSellerId)
                        .queryParam("from", PERIOD_FROM.toString())
                        .queryParam("to", PERIOD_TO.toString())
                        .queryParam("limit", "1")
                        .queryParam("cursor", firstCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        List<Integer> countsBefore = settlementTableCounts();
        mockMvc.perform(get("/api/sellers/{sellerId}/settlement-statement", firstSellerId)
                        .queryParam("from", PERIOD_FROM.toString())
                        .queryParam("to", PERIOD_TO.toString())
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(firstSellerId.toString()))
                .andExpect(jsonPath("$.totals.netLedgerChangeWon").value(200))
                .andExpect(jsonPath("$.items.length()").value(1));
        assertThat(settlementTableCounts()).isEqualTo(countsBefore);
    }

    @Test
    void unknownSellerStatementIsEmptyAndReadDoesNotWrite() throws Exception {
        UUID sellerId = UUID.randomUUID();
        List<Integer> countsBefore = settlementTableCounts();

        mockMvc.perform(get("/api/sellers/{sellerId}/settlement-statement", sellerId)
                        .queryParam("from", PERIOD_FROM.toString())
                        .queryParam("to", PERIOD_TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totals.capturedAmountWon").value(0))
                .andExpect(jsonPath("$.totals.refundReversalAmountWon").value(0))
                .andExpect(jsonPath("$.totals.netLedgerChangeWon").value(0));

        assertThat(settlementTableCounts()).isEqualTo(countsBefore);
    }

    @Test
    void missingOrderStillUsesTheExistingOrderErrorContract() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }


    @Test
    void statementTotalsStayExactWhenValidCheckoutAllocationsExceedTheLongRange() {
        UUID sellerId = sellers.createSeller().sellerId();
        String maxSkuId = registerSku(sellerId, Long.MAX_VALUE, 1);
        String oneSkuId = registerSku(sellerId, 1, 1);
        String maxBuyerRef = "statement-max-buyer-" + UUID.randomUUID();
        String oneBuyerRef = "statement-one-buyer-" + UUID.randomUUID();

        carts.setItemQuantity(sellerId, maxBuyerRef, maxSkuId, 1);
        CheckoutResponse maxCheckout = checkouts.checkout(
                maxBuyerRef, "statement-max-checkout-" + UUID.randomUUID());
        CheckoutResponse maxPaid = checkoutPayments.submit(maxCheckout.checkoutId());
        assertThat(maxPaid.orders()).singleElement().satisfies(order ->
                assertThat(order.state()).isEqualTo(OrderState.PAID));

        carts.setItemQuantity(sellerId, oneBuyerRef, oneSkuId, 1);
        CheckoutResponse oneCheckout = checkouts.checkout(
                oneBuyerRef, "statement-one-checkout-" + UUID.randomUUID());
        CheckoutResponse onePaid = checkoutPayments.submit(oneCheckout.checkoutId());
        assertThat(onePaid.orders()).singleElement().satisfies(order ->
                assertThat(order.state()).isEqualTo(OrderState.PAID));

        var statement = settlement.getSellerStatement(statementRequest(
                sellerId, PERIOD_FROM, PERIOD_TO, 10, null));
        BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        assertThat(statement.totals().capturedAmountWon()).isEqualTo(expected);
        assertThat(statement.totals().refundReversalAmountWon()).isEqualTo(BigInteger.ZERO);
        assertThat(statement.totals().netLedgerChangeWon()).isEqualTo(expected);
        assertThat(statement.items()).extracting(SellerSettlementStatementResponse.Entry::signedAmountWon)
                .containsExactlyInAnyOrder(Long.MAX_VALUE, 1L);
    }

    private String registerSku(UUID sellerId, long priceWon, int initialStock) {
        var product = products.createProduct(sellerId, new CreateProductRequest("Statement product", null));
        String skuId = "statement-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, priceWon, initialStock));
        return skuId;
    }

    private SellerSettlementStatementRequest statementRequest(
            UUID sellerId, Instant from, Instant to, int limit, String cursor) {
        return new SellerSettlementStatementRequest(sellerId, from, to, limit, cursor);
    }

    private List<Integer> settlementTableCounts() {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM settlement_journal", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM settlement_entry", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM settlement_seller_allocation", Integer.class));
    }
}
