package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.request.OrderRefundRequest;
import com.atlas.order.dto.request.OrderReturnRequest;
import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.exception.OrderException;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderResponse;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.persistence.state.FulfillmentStatus;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderShipmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderReturnService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
class OrderShipmentIntegrationTest extends PayPalIntegrationTestSupport {
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

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private InventoryService inventory;
    @Autowired private MockMvc mockMvc;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRefundService refunds;
    @Autowired private OrderFulfillmentService fulfillments;
    @Autowired private OrderShipmentService shipments;
    @Autowired private OrderReturnService returns;
    @Autowired private ObjectMapper mapper;

    @Test
    void fulfillmentStartShipmentReplayListDeliveryAndErrorContract() throws Exception {
        Fixture fixture = paidOrder();

        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNFULFILLED"));
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start",
                        fixture.sellerId(), fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/fulfillment/start",
                        fixture.sellerId(), fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        String request = "{\"carrierCode\":\"TEST\",\"trackingNumber\":\"TRACK-1\","
                + "\"items\":[{\"orderItemId\":\"" + fixture.orderItemId()
                + "\",\"quantity\":1}]}";
        String created = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/shipments",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "shipment-http-" + fixture.orderId())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andReturn().getResponse().getContentAsString();
        String shipmentId = mapper.readTree(created).get("shipmentId").toString().replace("\"", "");

        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/shipments",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "shipment-http-" + fixture.orderId())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(shipmentId));
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/shipments",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "shipment-http-" + fixture.orderId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("TRACK-1", "TRACK-2")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/buyers/{buyerRef}/orders/{orderId}/shipments",
                        buyerRef(fixture.orderId()), fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shipmentId").value(shipmentId))
                .andExpect(jsonPath("$[0].trackingNumber").value("TRACK-1"));
        mockMvc.perform(get("/api/buyers/{buyerRef}/orders/{orderId}/shipments",
                        "other-buyer", fixture.orderId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        fixture.sellerId(), fixture.orderId(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_SHIPMENT_NOT_FOUND"));
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        UUID.randomUUID(), fixture.orderId(), shipmentId))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        fixture.sellerId(), fixture.orderId(), shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/shipments/{shipmentId}/delivery",
                        fixture.sellerId(), fixture.orderId(), shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/fulfillment",
                        fixture.sellerId(), fixture.orderId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/shipments",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "invalid-shipment-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void partialRefundAllowsIncrementalShipmentsAndReturnOnlyAfterNetDelivery() throws Exception {
        Fixture fixture = paidOrder(3);
        refunds.refund(new OrderRefundRequest(fixture.sellerId(), fixture.orderId(),
                "partial-refund-" + fixture.orderId(),
                List.of(new OrderRefundRequest.Item(fixture.orderItemId(), 1))));
        fulfillments.start(fixture.sellerId(), fixture.orderId());
        var first = shipments.create(shipmentRequest(fixture, "partial-a-" + fixture.orderId(), 1));
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIALLY_SHIPPED"));
        assertThatThrownBy(() -> returns.request(new OrderReturnRequest(buyerRef(fixture.orderId()),
                fixture.orderId(), "early-return-" + fixture.orderId(),
                List.of(new OrderReturnRequest.Item(fixture.orderItemId(), 1)))))
                .isInstanceOf(OrderException.class);
        var second = shipments.create(shipmentRequest(fixture, "partial-b-" + fixture.orderId(), 1));
        assertThatThrownBy(() -> shipments.create(shipmentRequest(fixture, "partial-c-" + fixture.orderId(), 1)))
                .isInstanceOf(OrderException.class);
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThatThrownBy(() -> returns.request(new OrderReturnRequest(buyerRef(fixture.orderId()),
                fixture.orderId(), "return-before-delivery-" + fixture.orderId(),
                List.of(new OrderReturnRequest.Item(fixture.orderItemId(), 1)))))
                .isInstanceOf(OrderException.class);
        shipments.deliver(fixture.sellerId(), fixture.orderId(), first.shipmentId());
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThatThrownBy(() -> returns.request(new OrderReturnRequest(buyerRef(fixture.orderId()),
                fixture.orderId(), "return-after-one-delivery-" + fixture.orderId(),
                List.of(new OrderReturnRequest.Item(fixture.orderItemId(), 1)))))
                .isInstanceOf(OrderException.class);
        shipments.deliver(fixture.sellerId(), fixture.orderId(), second.shipmentId());
        mockMvc.perform(get("/api/orders/{orderId}/fulfillment", fixture.orderId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void shipmentProgressAggregatesAcrossOrderItemsAndLoadsShipmentLines() {
        MultiItemFixture fixture = paidOrderWithTwoItems();
        fulfillments.start(fixture.sellerId(), fixture.orderId());

        OrderShipmentResponse first = shipments.create(new OrderShipmentRequest(
                fixture.sellerId(), fixture.orderId(), "multi-shipment-first-" + fixture.orderId(), null, null,
                List.of(new OrderShipmentRequest.Item(fixture.firstOrderItemId(), 1))));
        assertThat(fulfillments.find(fixture.orderId()).status()).isEqualTo(FulfillmentStatus.PARTIALLY_SHIPPED);

        OrderShipmentResponse second = shipments.create(new OrderShipmentRequest(
                fixture.sellerId(), fixture.orderId(), "multi-shipment-second-" + fixture.orderId(), null, null,
                List.of(new OrderShipmentRequest.Item(fixture.secondOrderItemId(), 1))));
        assertThat(fulfillments.find(fixture.orderId()).status()).isEqualTo(FulfillmentStatus.SHIPPED);
        List<OrderShipmentResponse> listed = shipments.findAll(fixture.buyerRef(), fixture.orderId());
        assertThat(listed).hasSize(2).allSatisfy(shipment -> assertThat(shipment.items()).hasSize(1));

        shipments.deliver(fixture.sellerId(), fixture.orderId(), first.shipmentId());
        assertThat(fulfillments.find(fixture.orderId()).status()).isEqualTo(FulfillmentStatus.SHIPPED);
        shipments.deliver(fixture.sellerId(), fixture.orderId(), second.shipmentId());
        assertThat(fulfillments.find(fixture.orderId()).status()).isEqualTo(FulfillmentStatus.DELIVERED);
    }

    @Test
    void concurrentFinalRefundAndDispatchSerializeOnOrder() throws Exception {
        Fixture fixture = paidOrder(1);
        fulfillments.start(fixture.sellerId(), fixture.orderId());
        var barrier = new CyclicBarrier(3);
        var workers = Executors.newFixedThreadPool(2);
        Future<Boolean> refund = workers.submit(() -> {
            barrier.await();
            try {
                refunds.refund(new OrderRefundRequest(fixture.sellerId(), fixture.orderId(),
                        "race-refund-" + fixture.orderId(),
                        List.of(new OrderRefundRequest.Item(fixture.orderItemId(), 1))));
                return true;
            } catch (OrderException rejected) { return false; }
        });
        Future<Boolean> dispatch = workers.submit(() -> {
            barrier.await();
            try {
                shipments.create(shipmentRequest(fixture, "race-shipment-" + fixture.orderId(), 1));
                return true;
            } catch (OrderException rejected) { return false; }
        });
        barrier.await();
        boolean refundWon;
        boolean dispatchWon;
        try {
            refundWon = refund.get();
            dispatchWon = dispatch.get();
        } finally {
            workers.shutdownNow();
        }
        assertThat(refundWon).isNotEqualTo(dispatchWon);
        Integer shipmentCount = jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=?", Integer.class, fixture.orderId());
        assertThat(shipmentCount).isEqualTo(dispatchWon ? 1 : 0);
        Integer refundCount = jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_request WHERE order_id=?", Integer.class, fixture.orderId());
        assertThat(refundCount).isEqualTo(refundWon ? 1 : 0);
        assertThat(fulfillments.find(fixture.orderId()).status().name()).isEqualTo(dispatchWon ? "SHIPPED" : "IN_PROGRESS");
        String skuId = jdbc.queryForObject(
                "SELECT sku_id FROM order_item WHERE order_item_id = ?", String.class, fixture.orderItemId());
        var stock = inventory.find(fixture.sellerId(), skuId);
        assertThat(stock.available()).isEqualTo(refundWon ? 1L : 0L);
        assertThat(stock.claimed()).isEqualTo(refundWon ? 0L : 1L);
        assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(stock.initialCapacity());
    }

    private String buyerRef(UUID orderId) {
        return jdbc.queryForObject("SELECT buyer_ref FROM purchase_order WHERE order_id = ?", String.class, orderId);
    }

    private OrderShipmentRequest shipmentRequest(Fixture fixture, String key, int quantity) {
        return new OrderShipmentRequest(fixture.sellerId(), fixture.orderId(), key, null, null,
                List.of(new OrderShipmentRequest.Item(fixture.orderItemId(), quantity)));
    }

    private Fixture paidOrder(int quantity) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Shipment partial test", null));
        String skuId = "shipment-partial-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 250, quantity));
        String buyerRef = "shipment-partial-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, quantity);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "shipment-partial-checkout-" + UUID.randomUUID());
        OrderResponse reserved = checkout.orders().getFirst();
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, reserved.orderId());
        OrderResponse paid = completePayPalCheckout(checkout.checkoutId()).orders().getFirst();
        assertThat(paid.state().name()).isEqualTo("PAID");
        return new Fixture(sellerId, paid.orderId(), orderItemId);
    }

    private MultiItemFixture paidOrderWithTwoItems() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Shipment multi-item test", null));
        String firstSku = "shipment-a-" + UUID.randomUUID();
        String secondSku = "shipment-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(firstSku, 250, 1));
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(secondSku, 400, 1));
        String buyerRef = "shipment-multi-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, firstSku, 1);
        carts.setItemQuantity(sellerId, buyerRef, secondSku, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "shipment-multi-checkout-" + UUID.randomUUID());
        OrderResponse reserved = checkout.orders().getFirst();
        UUID firstItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), firstSku);
        UUID secondItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), secondSku);
        OrderResponse paid = completePayPalCheckout(checkout.checkoutId()).orders().getFirst();
        assertThat(paid.state().name()).isEqualTo("PAID");
        return new MultiItemFixture(sellerId, paid.orderId(), buyerRef, firstItemId, secondItemId);
    }

    private Fixture paidOrder() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new CreateProductRequest("Shipment HTTP test", null));
        String skuId = "shipment-http-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new RegisterSellerSkuRequest(skuId, 250, 1));
        String buyerRef = "shipment-http-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "shipment-http-checkout-" + UUID.randomUUID());
        OrderResponse reserved = checkout.orders().getFirst();
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, reserved.orderId());
        OrderResponse paid = completePayPalCheckout(checkout.checkoutId()).orders().getFirst();
        assertThat(paid.state().name()).isEqualTo("PAID");
        return new Fixture(sellerId, paid.orderId(), orderItemId);
    }

    private record MultiItemFixture(UUID sellerId, UUID orderId, String buyerRef,
            UUID firstOrderItemId, UUID secondOrderItemId) {
    }

    private CheckoutResponse completePayPalCheckout(UUID checkoutId) {
        CheckoutResponse pending = checkoutPayments.submit(checkoutId);
        return checkoutPayments.completeApproval(checkoutId, approvalToken(pending.paymentApprovalUrl()));
    }
    private record Fixture(UUID sellerId, UUID orderId, UUID orderItemId) {
    }
}
