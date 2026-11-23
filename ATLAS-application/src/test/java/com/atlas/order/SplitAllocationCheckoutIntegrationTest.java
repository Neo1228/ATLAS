package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.dto.response.ProductResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderShipmentRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderShipmentResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderShipmentService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * 실험 1(EXP-054): W1(A40,B0), W2(A0,B35), W3(A30,B30)에서 A1+B1 주문 70건을 실제 checkout·coordinator 경로로 처리한다.
 * 원격 location 3곳은 재고를 원격 예약으로만 차감하는 in-memory fake이며, 주문 DB는 PostgreSQL이다.
 */
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
class SplitAllocationCheckoutIntegrationTest extends PayPalIntegrationTestSupport {
    private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID W3 = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final String SERVICE_TOKEN = "integration-split-service-token-32bytes";
    private static final FakeLocations LOCATIONS = new FakeLocations();
    private static final MockWebServer INVENTORY = startServer();

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
        properties.add("atlas.order-inventory-reservation.location-endpoints", () -> {
            String base = INVENTORY.url("/").toString().replaceAll("/$", "");
            return W1 + "=" + base + ";" + W2 + "=" + base + ";" + W3 + "=" + base;
        });
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartCheckoutService cartCheckouts;
    @Autowired private OrderFulfillmentService fulfillment;
    @Autowired private OrderShipmentService shipments;

    @AfterAll
    static void shutdown() throws IOException {
        INVENTORY.shutdown();
    }

    @Test
    void singleLocationPolicyStopsAtThirtyWhileSplitReachesSixtyFive() throws Exception {
        assertThat(runScenario(false)).isEqualTo(new Outcome(30, 40, 0, 0, 0));
        assertThat(runScenario(true)).isEqualTo(new Outcome(65, 5, 0, 0, 35));
    }

    @Test
    void splitOrderShipsPerLocationWithinEachAllocation() throws Exception {
        Object target = AopTestUtils.getTargetObject(cartCheckouts);
        ReflectionTestUtils.setField(target, "splitAllocationEnabled", true);
        ensureFixture();
        LOCATIONS.reset(Map.of(W1, Map.of(skuA, 1L, skuB, 0L), W2, Map.of(skuA, 0L, skuB, 1L),
                W3, Map.of(skuA, 0L, skuB, 0L)));
        String buyer = "exp1-ship-" + UUID.randomUUID();
        addToCart(buyer, seller, skuA);
        addToCart(buyer, seller, skuB);
        CheckoutResponse reserved = mapper.readValue(mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", buyer)
                        .header("Idempotency-Key", "exp1-ship-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(reserved.state()).isEqualTo(BuyerCheckoutState.RESERVED);
        UUID orderId = reserved.orders().getFirst().orderId();
        assertThat(reserved.orders().getFirst().fulfillmentLocationId()).isNull();

        CheckoutResponse pending = mapper.readValue(mockMvc.perform(
                        post("/api/checkouts/{checkoutId}/payment", reserved.checkoutId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        String token = pending.paymentApprovalUrl().substring(pending.paymentApprovalUrl().lastIndexOf('/') + 1);
        CheckoutResponse paid = mapper.readValue(mockMvc.perform(
                        get("/api/checkouts/{checkoutId}/payment/return", reserved.checkoutId()).param("token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CheckoutResponse.class);
        assertThat(paid.state()).isEqualTo(BuyerCheckoutState.PAID);
        fulfillment.start(seller, orderId);
        UUID itemA = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, orderId, skuA);
        UUID itemB = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, orderId, skuB);

        assertThatThrownBy(() -> shipments.create(shipment(orderId, itemA, null)))
                .isInstanceOf(OrderException.class).hasMessageContaining("specify a fulfillment location");
        assertThatThrownBy(() -> shipments.create(shipment(orderId, itemA, W2)))
                .isInstanceOf(OrderException.class).hasMessageContaining("allocated to the fulfillment location");
        OrderShipmentResponse fromW1 = shipments.create(shipment(orderId, itemA, W1));
        OrderShipmentResponse fromW2 = shipments.create(shipment(orderId, itemB, W2));

        assertThat(fromW1.fulfillmentLocationId()).isEqualTo(W1);
        assertThat(fromW2.fulfillmentLocationId()).isEqualTo(W2);
        assertThat(jdbc.queryForObject("SELECT status FROM order_fulfillment WHERE order_id = ?", String.class, orderId))
                .isEqualTo("SHIPPED");
    }

    private OrderShipmentRequest shipment(UUID orderId, UUID orderItemId, UUID location) {
        return new OrderShipmentRequest(seller, orderId, "ship-" + UUID.randomUUID(), null, null,
                List.of(new OrderShipmentRequest.Item(orderItemId, 1)), location);
    }

    private static UUID seller;
    private static String skuA;
    private static String skuB;

    private void ensureFixture() {
        if (seller != null) return;
        seller = sellers.createSeller().sellerId();
        skuA = sku(seller, "A");
        skuB = sku(seller, "B");
        int priority = 0;
        for (UUID location : List.of(W1, W2, W3)) {
            jdbc.update("INSERT INTO inventory_location(location_id,seller_id,location_code,name,priority,active)"
                    + " VALUES (?,?,?,?,?,TRUE)", location, seller, "EXP1-" + priority, "W" + (priority + 1), priority);
            priority++;
        }
    }

    private Outcome runScenario(boolean split) throws Exception {
        Object target = AopTestUtils.getTargetObject(cartCheckouts);
        ReflectionTestUtils.setField(target, "splitAllocationEnabled", split);
        ensureFixture();
        LOCATIONS.reset(Map.of(
                W1, Map.of(skuA, 40L, skuB, 0L),
                W2, Map.of(skuA, 0L, skuB, 35L),
                W3, Map.of(skuA, 30L, skuB, 30L)));
        int reserved = 0;
        int rejected = 0;
        int splitOrders = 0;
        for (int order = 0; order < 70; order++) {
            String buyer = "exp1-" + split + "-" + order + "-" + UUID.randomUUID();
            addToCart(buyer, seller, skuA);
            addToCart(buyer, seller, skuB);
            var result = mockMvc.perform(post("/api/buyers/{buyer}/cart/checkout", buyer)
                    .header("Idempotency-Key", "exp1-" + UUID.randomUUID())).andReturn().getResponse();
            CheckoutResponse body = result.getStatus() == 200
                    ? mapper.readValue(result.getContentAsString(), CheckoutResponse.class) : null;
            if (body != null && body.state() == BuyerCheckoutState.RESERVED) {
                reserved++;
                // 여러 location으로 나뉜 주문은 단일 출고지가 없어 fulfillmentLocationId가 비어 있다.
                if (body.orders().getFirst().fulfillmentLocationId() == null) splitOrders++;
            } else {
                rejected++;
            }
        }
        return new Outcome(reserved, rejected, LOCATIONS.remaining(W3), LOCATIONS.oversold(), splitOrders);
    }

    private String sku(UUID seller, String label) {
        ProductResponse product = products.createProduct(seller,
                new CreateProductRequest("exp1-" + label + "-" + UUID.randomUUID(), null));
        String sku = "exp1-" + label + "-" + UUID.randomUUID();
        products.registerSku(seller, product.productId(), new RegisterSellerSkuRequest(sku, 100, 0));
        return sku;
    }

    private void addToCart(String buyer, UUID seller, String sku) throws Exception {
        mockMvc.perform(put("/api/buyers/{buyer}/cart/items/{sellerId}/{sku}", buyer, seller, sku)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isOk());
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(LOCATIONS);
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** reserved: 예약 성공, rejected: 실패, w3Remaining: W3 잔여 A+B, oversold: 음수 재고 횟수, splitOrders: 분할 주문 수. */
    private record Outcome(int reserved, int rejected, long w3Remaining, int oversold, int splitOrders) {
    }

    /** 원격 location 3곳의 재고를 원격 예약·취소로만 바꾸는 fake. 재고가 음수가 되면 oversold로 센다. */
    private static final class FakeLocations extends Dispatcher {
        private static final Pattern LOCATION = Pattern.compile("/fulfillment-locations/([0-9a-f-]{36})/");
        private static final Pattern STOCK = Pattern.compile("/availability/[0-9a-f-]{36}/([^/?]+)$");
        private static final Pattern ITEM = Pattern.compile(
                "\\{\"sellerId\":\"[0-9a-f-]{36}\",\"skuId\":\"([^\"]+)\",\"quantity\":(\\d+)}");
        private final Map<UUID, Map<String, Long>> stock = new HashMap<>();
        private final Map<String, Map<String, Long>> reservations = new HashMap<>();
        private int oversold;

        synchronized void reset(Map<UUID, Map<String, Long>> initial) {
            stock.clear();
            reservations.clear();
            oversold = 0;
            initial.forEach((location, skus) -> stock.put(location, new HashMap<>(skus)));
        }

        synchronized long remaining(UUID location) {
            return stock.get(location).values().stream().mapToLong(Long::longValue).sum();
        }

        synchronized int oversold() {
            return oversold;
        }

        @Override
        public synchronized MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            Matcher locationMatch = LOCATION.matcher(path);
            if (!locationMatch.find()) return new MockResponse().setResponseCode(404);
            UUID location = UUID.fromString(locationMatch.group(1));
            Map<String, Long> skus = stock.getOrDefault(location, Map.of());
            Matcher stockMatch = STOCK.matcher(path);
            if ("GET".equals(request.getMethod()) && stockMatch.find()) {
                return json(200, "{\"available\":" + skus.getOrDefault(stockMatch.group(1), 0L) + "}");
            }
            if (path.endsWith("/claim") && "POST".equals(request.getMethod())) {
                String reservationId = path.substring(path.indexOf("/reservations/") + 14, path.lastIndexOf('/'));
                return json(200, "{\"reservationId\":\"" + reservationId + "\",\"status\":\"CLAIMED\","
                        + "\"expiresAt\":\"" + Instant.now().plusSeconds(60) + "\"}");
            }
            String key = location + ":" + request.getHeader("Idempotency-Key");
            if (path.endsWith("/reservations/cancel")) {
                Map<String, Long> held = reservations.remove(key);
                if (held != null) held.forEach((sku, quantity) -> stock.get(location).merge(sku, quantity, Long::sum));
                return json(200, "{\"reservationId\":\"" + UUID.randomUUID() + "\",\"status\":\"CANCELLED\"}");
            }
            if (path.endsWith("/reservations") && "POST".equals(request.getMethod())) {
                Map<String, Long> wanted = new HashMap<>();
                Matcher item = ITEM.matcher(request.getBody().readUtf8());
                while (item.find()) wanted.merge(item.group(1), Long.parseLong(item.group(2)), Long::sum);
                if (!reservations.containsKey(key)) {
                    boolean enough = wanted.entrySet().stream()
                            .allMatch(line -> stock.get(location).getOrDefault(line.getKey(), 0L) >= line.getValue());
                    if (!enough) return json(409, "{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"no stock\"}");
                    wanted.forEach((sku, quantity) -> {
                        long next = stock.get(location).merge(sku, -quantity, Long::sum);
                        if (next < 0) oversold++;
                    });
                    reservations.put(key, wanted);
                }
                return json(201, "{\"reservationId\":\"" + UUID.nameUUIDFromBytes(key.getBytes()) + "\","
                        + "\"status\":\"RESERVED\",\"expiresAt\":\"" + Instant.now().plusSeconds(120) + "\"}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(int status, String body) {
            return new MockResponse().setResponseCode(status)
                    .addHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).setBody(body);
        }
    }
}
