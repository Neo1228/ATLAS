package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;

import com.atlas.admission.dto.request.JoinQueueRequest;
import com.atlas.admission.service.AdmissionService;
import com.atlas.campaign.dto.request.CreateCampaignRequest;
import com.atlas.campaign.dto.response.CampaignResponse;
import com.atlas.campaign.service.CampaignService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.dto.request.CreateFulfillmentLocationRequest;
import com.atlas.inventory.dto.request.ReserveInventoryBatchRequest;
import com.atlas.inventory.dto.response.InventoryLocationResponse;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.OrderReturnResponse;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.service.OrderService;
import com.atlas.payment.persistence.state.FakePspResult;
import com.atlas.payment.persistence.state.PaymentPhase;
import com.atlas.payment.provider.fake.FakePsp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class LocationRoutedCheckoutIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private InventoryCapacityAdjustmentService adjustments;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private OrderService orders;
    @Autowired private FakePsp fakePsp;
    @Autowired private ObjectMapper mapper;

    @Test
    void routeSelectionUsesPriorityThenUuidAndNeverCombinesLocationsForSkuLines() {
        UUID sellerId = sellers.createSeller().sellerId();
        String firstSku = createSku(sellerId, "route-a", 0);
        String secondSku = createSku(sellerId, "route-b", 0);
        UUID highUuid = UUID.fromString("20000000-0000-7000-8000-000000000001");
        UUID lowUuid = UUID.fromString("10000000-0000-7000-8000-000000000001");
        insertLocation(sellerId, highUuid, "TIE-B", "Tie B", 1);
        insertLocation(sellerId, lowUuid, "TIE-A", "Tie A", 1);
        stock(sellerId, highUuid, firstSku, 3);
        stock(sellerId, highUuid, secondSku, 3);
        stock(sellerId, lowUuid, firstSku, 3);
        stock(sellerId, lowUuid, secondSku, 3);
        UUID priorityUuid = UUID.fromString("00000000-0000-7000-8000-000000000001");
        insertLocation(sellerId, priorityUuid, "PRIORITY", "Priority winner", 0);
        stock(sellerId, priorityUuid, firstSku, 3);
        stock(sellerId, priorityUuid, secondSku, 3);
        assertThat(inventory.findFulfillmentLocation(sellerId, List.of(
                new ReserveInventoryBatchRequest.Item(sellerId, firstSku, 1),
                new ReserveInventoryBatchRequest.Item(sellerId, secondSku, 1)))).contains(priorityUuid);
        jdbc.update("UPDATE inventory_location SET active=FALSE WHERE location_id=?", priorityUuid);
        assertThat(inventory.findFulfillmentLocation(sellerId, List.of(
                new ReserveInventoryBatchRequest.Item(sellerId, firstSku, 1),
                new ReserveInventoryBatchRequest.Item(sellerId, secondSku, 1)))).contains(lowUuid);

        UUID splitSeller = sellers.createSeller().sellerId();
        String splitA = createSku(splitSeller, "split-a", 0);
        String splitB = createSku(splitSeller, "split-b", 0);
        InventoryLocationResponse left = inventory.createLocation(new CreateFulfillmentLocationRequest(
                splitSeller, "LEFT-" + UUID.randomUUID(), "Left", 0));
        InventoryLocationResponse right = inventory.createLocation(new CreateFulfillmentLocationRequest(
                splitSeller, "RIGHT-" + UUID.randomUUID(), "Right", 1));
        adjustments.adjust(splitSeller, splitA, left.locationId(), "split-a-" + UUID.randomUUID(), 2, "seed");
        adjustments.adjust(splitSeller, splitB, right.locationId(), "split-b-" + UUID.randomUUID(), 2, "seed");
        assertThat(inventory.findFulfillmentLocation(splitSeller, List.of(
                new ReserveInventoryBatchRequest.Item(splitSeller, splitA, 1),
                new ReserveInventoryBatchRequest.Item(splitSeller, splitB, 1)))).isEmpty();
    }

    @Test
    void failedSellerCheckoutRollsBackWholeBuyerCartAndDifferentSellersSelectDifferentLocations() throws Exception {
        UUID stockedSeller = sellers.createSeller().sellerId();
        UUID splitSeller = sellers.createSeller().sellerId();
        String stockedSku = createSku(stockedSeller, "atomic-stocked", 2);
        String splitA = createSku(splitSeller, "atomic-split-a", 0);
        String splitB = createSku(splitSeller, "atomic-split-b", 0);
        InventoryLocationResponse left = inventory.createLocation(new CreateFulfillmentLocationRequest(
                splitSeller, "LEFT-" + UUID.randomUUID(), "Left", 0));
        InventoryLocationResponse right = inventory.createLocation(new CreateFulfillmentLocationRequest(
                splitSeller, "RIGHT-" + UUID.randomUUID(), "Right", 1));
        adjustments.adjust(splitSeller, splitA, left.locationId(), "left-" + UUID.randomUUID(), 2, "seed");
        adjustments.adjust(splitSeller, splitB, right.locationId(), "right-" + UUID.randomUUID(), 2, "seed");
        String buyer = "location-rollback-" + UUID.randomUUID();
        addCartItem(buyer, stockedSeller, stockedSku);
        addCartItem(buyer, splitSeller, splitA);
        addCartItem(buyer, splitSeller, splitB);

        mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", buyer)
                        .header("Idempotency-Key", "rollback-" + UUID.randomUUID()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM buyer_checkout WHERE buyer_ref=?", Integer.class, buyer))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE buyer_ref=?", Integer.class, buyer))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE seller_id=? AND sku_id=?",
                Integer.class, stockedSeller, stockedSku)).isZero();
        assertThat(inventory.find(stockedSeller, stockedSku).available()).isEqualTo(2);
        assertThat(cartItemCount(buyer)).isEqualTo(3);

        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        String sharedSku = "shared-location-" + UUID.randomUUID();
        createSkuWithId(sellerA, "multi-a", sharedSku, 0);
        createSkuWithId(sellerB, "multi-b", sharedSku, 0);
        InventoryLocationResponse locationA = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerA, "PICK-" + UUID.randomUUID(), "Pick A", 0));
        InventoryLocationResponse locationB = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerB, "PICK-" + UUID.randomUUID(), "Pick B", 0));
        stock(sellerA, locationA.locationId(), sharedSku, 3);
        stock(sellerB, locationB.locationId(), sharedSku, 3);
        String multiBuyer = "location-multi-" + UUID.randomUUID();
        addCartItem(multiBuyer, sellerA, sharedSku);
        addCartItem(multiBuyer, sellerB, sharedSku);
        CheckoutResponse checkout = mapper.readValue(mockMvc.perform(
                        post("/api/buyers/{buyer}/cart/checkout", multiBuyer)
                                .header("Idempotency-Key", "multi-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(checkout.orders()).hasSize(2);
        assertThat(checkout.orders().stream().map(this::orderLocation).toList())
                .containsExactlyInAnyOrder(locationA.locationId(), locationB.locationId());
    }

    @Test
    void campaignReservationAndCancellationUseThePersistedLocation() {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, "campaign-location", 0);
        InventoryLocationResponse location = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "CAMPAIGN-" + UUID.randomUUID(), "Campaign stock", 0));
        adjustments.adjust(sellerId, sku, location.locationId(), "campaign-stock-" + UUID.randomUUID(), 4, "seed");
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, sku, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(sellerId, sku, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());
        var ticket = admission.join(new JoinQueueRequest(campaign.campaignId(), sku,
                "campaign-buyer-" + UUID.randomUUID(), "campaign-join-" + UUID.randomUUID()));
        OrderResponse order = orders.startCheckout(campaign.campaignId(), ticket.ticketId());
        UUID selectedLocation = orderLocation(order);
        assertThat(selectedLocation).isEqualTo(location.locationId());

        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.PENDING, FakePspResult.AUTHORIZED);
        orders.submitPayment(order.orderId());
        String cancelKey = "location-cancel-" + UUID.randomUUID();
        orders.cancel(order.orderId(), cancelKey);
        orders.cancel(order.orderId(), cancelKey + "-retry");
        assertThat(jdbc.queryForObject("SELECT location_id FROM inventory_reservation WHERE order_id=?",
                UUID.class, order.orderId())).isEqualTo(selectedLocation);
        assertThat(inventory.findLocationInventory(sellerId, selectedLocation)).singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualTo(4));
    }

    @Test
    void paymentAndCancelHttpRoutesKeepTheReservationLocation() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, "route-payment", 0);
        InventoryLocationResponse location = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "PAYMENT-" + UUID.randomUUID(), "Payment location", 0));
        adjustments.adjust(sellerId, sku, location.locationId(), "payment-stock-" + UUID.randomUUID(), 1, "seed");
        admission.configureSellerSkuPolicy(new ConfigureSellerSkuPolicyRequest(sellerId, sku, 1));
        CampaignResponse campaign = campaigns.create(new CreateCampaignRequest(sellerId, sku, Duration.ofMinutes(5)));
        campaigns.open(campaign.campaignId());
        var ticket = admission.join(new JoinQueueRequest(campaign.campaignId(), sku,
                "route-payment-buyer-" + UUID.randomUUID(), "route-payment-ticket-" + UUID.randomUUID()));
        OrderResponse order = mapper.readValue(mockMvc.perform(
                        post("/api/campaigns/{campaignId}/tickets/{ticketId}/checkout",
                                campaign.campaignId(), ticket.ticketId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), OrderResponse.class);

        fakePsp.enqueue(PaymentPhase.AUTHORIZATION, FakePspResult.PENDING, FakePspResult.AUTHORIZED);
        mockMvc.perform(post("/api/orders/{orderId}/payment", order.orderId()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId())
                        .header("Idempotency-Key", "route-cancel-" + UUID.randomUUID()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{orderId}/cancel", order.orderId())
                        .header("Idempotency-Key", "route-cancel-retry-" + UUID.randomUUID()))
                .andExpect(status().isOk());

        assertThat(orderLocation(orders.find(order.orderId()))).isEqualTo(location.locationId());
        assertThat(jdbc.queryForObject("SELECT location_id FROM inventory_reservation WHERE order_id=?",
                UUID.class, order.orderId())).isEqualTo(location.locationId());
        assertThat(inventory.findLocationInventory(sellerId, location.locationId())).singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualTo(1));
    }

    @Test
    void passedReturnRestocksTheOriginalLocationAfterRoutingPriorityChanges() throws Exception {
        UUID sellerId = sellers.createSeller().sellerId();
        String sku = createSku(sellerId, "return-location", 0);
        InventoryLocationResponse origin = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "ORIGIN-" + UUID.randomUUID(), "Original", 10));
        adjustments.adjust(sellerId, sku, origin.locationId(), "origin-stock-" + UUID.randomUUID(), 1,
                "initial location stock");

        String buyerRef = "location-return-" + UUID.randomUUID();
        addCartItem(buyerRef, sellerId, sku);
        String checkoutJson = mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", buyerRef)
                        .header("Idempotency-Key", "location-checkout-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        CheckoutResponse checkout = mapper.readValue(checkoutJson, CheckoutResponse.class);
        OrderResponse order = checkout.orders().getFirst();
        assertThat(order.fulfillmentLocationId()).isEqualTo(origin.locationId());

        CheckoutResponse paid = mapper.readValue(mockMvc.perform(
                        post("/api/checkouts/{checkoutId}/payment", checkout.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(paid.orders().getFirst().state().name()).isEqualTo("PAID");

        InventoryLocationResponse preferred = inventory.createLocation(new CreateFulfillmentLocationRequest(
                sellerId, "PREFERRED-" + UUID.randomUUID(), "Preferred", 0));
        adjustments.adjust(sellerId, sku, preferred.locationId(), "preferred-stock-" + UUID.randomUUID(), 1,
                "new preferred location stock");
        assertThat(inventory.findFulfillmentLocation(sellerId, List.of(
                new ReserveInventoryBatchRequest.Item(sellerId, sku, 1)))).contains(preferred.locationId());

        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start",
                        sellerId, order.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id=?", UUID.class, order.orderId());
        String shipmentJson = mockMvc.perform(post(
                        "/api/sellers/{sellerId}/orders/{orderId}/shipments", sellerId, order.orderId())
                        .header("Idempotency-Key", "location-shipment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":\"" + orderItemId + "\",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andReturn().getResponse().getContentAsString();
        UUID shipmentId = mapper.readValue(shipmentJson, OrderShipmentResponse.class).shipmentId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        sellerId, order.orderId(), shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        String returnJson = mockMvc.perform(post(
                        "/api/buyers/{buyerRef}/orders/{orderId}/returns", buyerRef, order.orderId())
                        .header("Idempotency-Key", "location-return-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":\"" + orderItemId + "\",\"quantity\":1}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID returnId = mapper.readValue(returnJson, OrderReturnResponse.class).returnId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/receipt",
                        sellerId, order.orderId(), returnId))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection",
                        sellerId, order.orderId(), returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":\"" + orderItemId + "\",\"result\":\"PASSED\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_RECORDED"));

        assertThat(orderLocation(orders.find(order.orderId()))).isEqualTo(origin.locationId());
        assertThat(jdbc.queryForObject("SELECT location_id FROM inventory_reservation WHERE order_id=?",
                UUID.class, order.orderId())).isEqualTo(origin.locationId());
        assertThat(inventory.findLocationInventory(sellerId, origin.locationId())).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.skuId()).isEqualTo(sku);
                    assertThat(stock.available()).isEqualTo(1);
                    assertThat(stock.claimed()).isZero();
                });
        assertThat(inventory.findLocationInventory(sellerId, preferred.locationId())).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.skuId()).isEqualTo(sku);
                    assertThat(stock.available()).isEqualTo(1);
                    assertThat(stock.claimed()).isZero();
                });
    }

    private String createSku(UUID sellerId, String label, int capacity) {
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest(label + "-" + UUID.randomUUID(), null));
        String sku = label + "-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(sku, 100, capacity));
        return sku;
    }

    private void createSkuWithId(UUID sellerId, String label, String sku, int capacity) {
        ProductResponse product = products.createProduct(sellerId,
                new CreateProductRequest(label + "-" + UUID.randomUUID(), null));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(sku, 100, capacity));
    }

    private void insertLocation(UUID sellerId, UUID locationId, String code, String name, int priority) {
        jdbc.update("INSERT INTO inventory_location(location_id,seller_id,location_code,name,priority,active) VALUES (?,?,?,?,?,TRUE)",
                locationId, sellerId, code, name, priority);
    }

    private void stock(UUID sellerId, UUID locationId, String sku, int quantity) {
        adjustments.adjust(sellerId, sku, locationId, "seed-" + UUID.randomUUID(), quantity, "seed stock");
    }

    private void addCartItem(String buyer, UUID sellerId, String sku) throws Exception {
        mockMvc.perform(put("/api/buyers/{buyer}/cart/items/{sellerId}/{sku}", buyer, sellerId, sku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
    }

    private int cartItemCount(String buyer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart_item i JOIN shopping_cart c ON c.cart_id=i.cart_id WHERE c.buyer_ref=?",
                Integer.class, buyer);
    }

    private UUID orderLocation(OrderResponse order) {
        return jdbc.queryForObject("SELECT fulfillment_location_id FROM purchase_order WHERE order_id=?",
                UUID.class, order.orderId());
    }
}
