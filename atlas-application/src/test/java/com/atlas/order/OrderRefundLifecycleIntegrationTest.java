package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.dto.response.ReturnResponse;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.inventory.dto.response.InventoryResponse;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.exception.CatalogException;
import com.atlas.order.service.CartService;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.inventory.service.InventoryService;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.model.FulfillmentStatus;
import com.atlas.order.model.OrderRefundStatus;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderFulfillmentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderShipmentService;
import com.atlas.order.repository.OrderRefundRepository;
import com.atlas.order.service.OrderReturnService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@SpringBootTest
class OrderRefundLifecycleIntegrationTest extends PayPalIntegrationTestSupport {
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
        // 운영자 환불 API(ADR-0112)를 켠다. 이 클래스는 MockMvc 필터를 끄므로 운영자 식별자는 principal로 넣는다.
        properties.add("atlas.account.suspension.operator.enabled", () -> "true");
        properties.add("atlas.account.suspension.operator.username", () -> "operator-7");
        properties.add("atlas.account.suspension.operator.password", () -> "operator-password-at-least-32-chars-long");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private OrderService orders;
    @Autowired private OrderRefundService refunds;
    @Autowired private OrderReturnService returns;
    @Autowired private InventoryService inventory;
    @Autowired
    private OrderFulfillmentService fulfillments;
    @Autowired
    private OrderShipmentService shipments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRefundRepository refundRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;

    @Test
    void returnRequestIsDeliveryGatedAndReceiptDoesNotRefundOrRestock() {
        Fixture fixture = paidOrder(1, 2, 125);
        String buyerRef = buyerRef(fixture.orderId());
        OrderRequest.Return request = returnRequest(buyerRef, fixture.orderId(), "return-delivery-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1));
        assertThatThrownBy(() -> returns.request(request)).isInstanceOf(OrderException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_return WHERE order_id = ?",
                Integer.class, fixture.orderId())).isZero();
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertStock(fixture, 1, 1);
        deliver(fixture);
        ReturnResponse.OrderReturn created = returns.request(request);
        assertThatThrownBy(() -> returns.receive(UUID.randomUUID(), fixture.orderId(), created.returnId()))
                .isInstanceOf(OrderException.class);
        assertThat(created.status()).isEqualTo(OrderReturnStatus.REQUESTED);
        ReturnResponse.OrderReturn received = returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        assertThat(received.status()).isEqualTo(OrderReturnStatus.RECEIVED);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertStock(fixture, 1, 1);
        assertThat(returns.request(request)).isEqualTo(received);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "return-delivery-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 2)))).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> returns.find("not-the-buyer", fixture.orderId(), created.returnId()))
                .isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> returns.find(buyerRef, fixture.orderId(), UUID.randomUUID()))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.NOT_FOUND));
    }
    @Test
    void returnRequestReceiptAndInspectionAreAvailableThroughHttpRoutes() throws Exception {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        mockMvc.perform(post("/api/buyers/{buyerRef}/orders/{orderId}/returns", buyerRef, fixture.orderId())
                        .header("Idempotency-Key", "http-null-return-item")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[null]}
                                """))
                .andExpect(status().isBadRequest());
        String response = mockMvc.perform(post("/api/buyers/{buyerRef}/orders/{orderId}/returns",
                        buyerRef, fixture.orderId())
                        .header("Idempotency-Key", "http-return-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":1}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_APPLICABLE"))
                .andReturn().getResponse().getContentAsString();
        UUID returnId = objectMapper.readValue(response, ReturnResponse.OrderReturn.class).returnId();
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/receipt",
                        fixture.sellerId(), fixture.orderId(), returnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.paymentStatus").value("NOT_APPLICABLE"));
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection",
                        fixture.sellerId(), fixture.orderId(), returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[null]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/sellers/{sellerId}/orders/{orderId}/returns/{returnId}/inspection",
                        fixture.sellerId(), fixture.orderId(), returnId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","result":"PASSED"}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUND_RECORDED"))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].refundId").exists());
    }

    @Test
    void sellerReturnQueueIsPagedAndExcludesBuyerReference() throws Exception {
        String indexDefinition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_order_return_seller_history'", String.class);
        assertThat(indexDefinition).contains("seller_id", "requested_at DESC", "return_id DESC");
        Fixture sellerA = paidOrder(2, 2, 125);
        deliver(sellerA);
        String buyerA = buyerRef(sellerA.orderId());
        ReturnResponse.OrderReturn returnA1 = returns.request(returnRequest(buyerA, sellerA.orderId(), "seller-return-a1",
                new OrderRequest.Return.Item(sellerA.orderItemId(), 1)));
        ReturnResponse.OrderReturn returnA2 = returns.request(returnRequest(buyerA, sellerA.orderId(), "seller-return-a2",
                new OrderRequest.Return.Item(sellerA.orderItemId(), 1)));

        Fixture sellerB = paidOrder(1, 1, 175);
        deliver(sellerB);
        String buyerB = buyerRef(sellerB.orderId());
        ReturnResponse.OrderReturn returnB = returns.request(returnRequest(buyerB, sellerB.orderId(), "seller-return-b",
                new OrderRequest.Return.Item(sellerB.orderItemId(), 1)));

        String firstBody = mockMvc.perform(get("/api/sellers/{sellerId}/returns", sellerA.sellerId())
                        .param("status", "REQUESTED")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ReturnResponse.SellerOrderReturnPage first = objectMapper.readValue(firstBody, ReturnResponse.SellerOrderReturnPage.class);
        assertThat(first.returns()).hasSize(1);
        assertThat(first.returns().getFirst().returnId()).isIn(returnA1.returnId(), returnA2.returnId());
        assertThat(first.returns().getFirst().sellerId()).isEqualTo(sellerA.sellerId());
        assertThat(first.returns().getFirst().orderId()).isEqualTo(sellerA.orderId());
        assertThat(first.returns().getFirst().items()).extracting(ReturnResponse.SellerOrderReturn.Item::orderItemId)
                .containsExactly(sellerA.orderItemId());
        assertThat(firstBody).doesNotContain(buyerA, buyerB, "buyerRef");
        assertThat(first.nextCursor()).isNotBlank();

        String secondBody = mockMvc.perform(get("/api/sellers/{sellerId}/returns", sellerA.sellerId())
                        .param("status", "REQUESTED")
                        .param("limit", "1")
                        .param("cursor", first.nextCursor()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ReturnResponse.SellerOrderReturnPage second = objectMapper.readValue(secondBody, ReturnResponse.SellerOrderReturnPage.class);
        assertThat(second.returns()).singleElement()
                .satisfies(row -> assertThat(row.returnId()).isNotEqualTo(first.returns().getFirst().returnId()));
        assertThat(second.returns().getFirst().returnId()).isIn(returnA1.returnId(), returnA2.returnId());
        assertThat(second.returns().getFirst().returnId()).isNotEqualTo(returnB.returnId());
        assertThat(second.nextCursor()).isNull();

        ReturnResponse.SellerOrderReturnPage sellerBPage = objectMapper.readValue(mockMvc.perform(
                        get("/api/sellers/{sellerId}/returns", sellerB.sellerId()).param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), ReturnResponse.SellerOrderReturnPage.class);
        assertThat(sellerBPage.returns()).extracting(ReturnResponse.SellerOrderReturn::returnId)
                .containsExactly(returnB.returnId());
        assertThat(returns.findForSeller(sellerA.sellerId(), OrderReturnStatus.RECEIVED, 10, null).returns())
                .isEmpty();
    }

    /**
     * 반품 환불의 재시도 회차 키가 올바르게 분기되는지 확인한다.
     *
     * <p>반품 환불의 idempotency key 는 "return-<returnId>" 파생값이라 호출자가 고를 수 없다.
     * 한 번 FAILED 가 되면 payment 계층이 terminal 로 처리해 같은 키로는 다시 송금하지 않는다.
     * 그래서 재시도 시 "-r<n>" 을 붙여 새 작업을 만드는데, 접두사 매칭이 다른 반품 키와
     * 섞이지 않아야 한다.
     *
     * <p>이 테스트는 저장소 수준에서 그 두 성질을 직접 고정한다. provider 실패를 인위적으로
     * 재현하는 통합 시나리오는 stub provider 의 동작에 의존해 불안정하므로, 키 계산 규칙을
     * 단위로 검증하는 편이 신뢰할 수 있다.
     */
    @Test
    void returnRefundRetryKeysDoNotCollideWithOtherReturns() {
        UUID returnA = UUID.randomUUID();
        UUID returnB = UUID.randomUUID();
        UUID sellerId = sellers.createSeller().sellerId();
        UUID orderId = paidOrder(1, 1, 100).orderId();

        // 초기에는 어떤 접두사와도 매칭되지 않는다.
        assertThat(refundRepository.countByKeyPrefix("return-" + returnA + "-r")).isZero();

        // B 의 키를 먼저 만든다. A 의 회차 계산에 영향을 주면 안 된다.
        jdbc.update("""
                INSERT INTO order_refund_request
                    (refund_id, order_id, seller_id, idempotency_key, amount_won, refund_status, created_at)
                VALUES (?, ?, ?, ?, 100, 'FULLY_RECORDED', now())
                """, UUID.randomUUID(), orderId, sellerId, "return-" + returnB);

        assertThat(refundRepository.countByKeyPrefix("return-" + returnA + "-r"))
                .as("다른 반품의 키가 접두사에 걸리면 회차가 틀어진다")
                .isZero();
        assertThat(refundRepository.countByKeyPrefix("return-" + returnB + "-r"))
                .as("B 자신의 회차 접두사에도 아직 매칭되지 않는다")
                .isZero();
    }

    @Test
    void mixedInspectionRefundsOnlyPassedLineAndFailedQuantityRemainsUnavailable() {
        MultiItemFixture fixture = paidOrderWithTwoItems();
        deliver(fixture.sellerId(), fixture.orderId());
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn created = returns.request(new OrderRequest.Return(buyerRef, fixture.orderId(),
                "return-mixed-key", List.of(new OrderRequest.Return.Item(fixture.firstItemId(), 1),
                new OrderRequest.Return.Item(fixture.secondItemId(), 1)), null, null));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        ReturnResponse.OrderReturn inspected = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(),
                new OrderRequest.ReturnInspection(List.of(
                        new OrderRequest.ReturnInspection.Item(fixture.firstItemId(), OrderRequest.ReturnInspection.Result.PASSED),
                        new OrderRequest.ReturnInspection.Item(fixture.secondItemId(), OrderRequest.ReturnInspection.Result.FAILED))));
        assertThat(inspected.status()).isEqualTo(OrderReturnStatus.PARTIALLY_RECORDED);
        assertThat(inspected.items()).filteredOn(item -> item.orderItemId().equals(fixture.firstItemId()))
                .singleElement().satisfies(item -> {
                    assertThat(item.refundId()).isNotNull();
                    assertThat(item.refundAmountWon()).isEqualTo(100L);
                });
        assertThat(inspected.items()).filteredOn(item -> item.orderItemId().equals(fixture.secondItemId()))
                .singleElement().satisfies(item -> assertThat(item.refundId()).isNull());
        assertThat(inventory.find(fixture.sellerId(), fixture.firstSkuId()).available()).isEqualTo(1);
        assertThat(inventory.find(fixture.sellerId(), fixture.firstSkuId()).claimed()).isZero();
        assertThat(inventory.find(fixture.sellerId(), fixture.secondSkuId()).claimed()).isEqualTo(1);
        assertThat(returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(),
                new OrderRequest.ReturnInspection(List.of(
                        new OrderRequest.ReturnInspection.Item(fixture.firstItemId(), OrderRequest.ReturnInspection.Result.PASSED),
                        new OrderRequest.ReturnInspection.Item(fixture.secondItemId(), OrderRequest.ReturnInspection.Result.FAILED)))))
                .isEqualTo(inspected);
        assertThatThrownBy(() -> returns.request(new OrderRequest.Return(buyerRef, fixture.orderId(),
                "failed-line-retry", List.of(new OrderRequest.Return.Item(fixture.secondItemId(), 1)), null, null)))
                .isInstanceOf(OrderException.class);
    }
    @Test
    void returnQuantityLimitIncludesEarlierRefundsAndPassedReturnsAcrossCases() {
        Fixture fixture = paidOrder(3, 3, 100);
        refunds.refund(request(fixture.sellerId(), fixture.orderId(), "before-return-refund",
                fixture.orderItemId(), 1));
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn first = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-two-units",
                new OrderRequest.Return.Item(fixture.orderItemId(), 2)));
        returns.receive(fixture.sellerId(), fixture.orderId(), first.returnId());
        ReturnResponse.OrderReturn passed = returns.inspect(fixture.sellerId(), fixture.orderId(), first.returnId(),
                new OrderRequest.ReturnInspection(List.of(new OrderRequest.ReturnInspection.Item(
                        fixture.orderItemId(), OrderRequest.ReturnInspection.Result.PASSED))));
        assertThat(passed.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "return-over-total",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
    }
    @Test
    void allFailedInspectionKeepsClaimedStockAndCannotBeRepeatedOrRequestedAgain() {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn created = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-failed-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1)));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderRequest.ReturnInspection failed = new OrderRequest.ReturnInspection(List.of(
                new OrderRequest.ReturnInspection.Item(fixture.orderItemId(), OrderRequest.ReturnInspection.Result.FAILED)));
        ReturnResponse.OrderReturn inspected = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), failed);
        assertThat(inspected.status()).isEqualTo(OrderReturnStatus.INSPECTION_FAILED);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertStock(fixture, 0, 1);
        assertThat(returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), failed))
                .isEqualTo(inspected);
        assertThatThrownBy(() -> returns.request(returnRequest(buyerRef, fixture.orderId(), "failed-quantity-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1)))).isInstanceOf(OrderException.class);
    }
    @Test
    void settlementFailureRollsBackRefundFinalizationAndRetainsPreparedInspectionForRetry() {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn created = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-rollback-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1)));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderRequest.ReturnInspection passed = new OrderRequest.ReturnInspection(List.of(
                new OrderRequest.ReturnInspection.Item(fixture.orderItemId(), OrderRequest.ReturnInspection.Result.PASSED)));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_return_settlement() RETURNS TRIGGER AS $$
                BEGIN
                  IF NEW.account_code = 'PSP_CLEARING' AND NEW.entry_side = 'CREDIT' THEN
                    RAISE EXCEPTION 'forced settlement failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_return_settlement_trigger
                BEFORE INSERT ON settlement_entry
                FOR EACH ROW EXECUTE FUNCTION fail_return_settlement()
                """);
        assertThatThrownBy(() -> returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), passed))
                .isInstanceOf(RuntimeException.class);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_return_settlement_trigger ON settlement_entry");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_return_settlement()");
        ReturnResponse.OrderReturn unchanged = returns.find(buyerRef, fixture.orderId(), created.returnId());
        assertThat(unchanged.status()).isEqualTo(OrderReturnStatus.RECEIVED);
        assertThat(unchanged.items().get(0).inspectionResult()).isEqualTo(OrderReturnInspectionResult.PASSED);
        assertThat(unchanged.items().get(0).refundId()).isNotNull();
        assertThat(unchanged.items().get(0).refundAmountWon()).isEqualTo(125L);
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertStock(fixture, 0, 1);
        ReturnResponse.OrderReturn retried = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(), passed);
        assertThat(retried.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(retried.items().get(0).refundId()).isEqualTo(unchanged.items().get(0).refundId());
        assertThat(retried.items().get(0).refundAmountWon()).isEqualTo(unchanged.items().get(0).refundAmountWon());
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
    }

    @Test
    void partialAndFullRefundsReplayAndRestoreOnlyRequestedUnitsWithoutChangingPaidState() {
        Fixture fixture = paidOrder(2, 5, 125);
        UUID itemId = fixture.orderItemId();
        OrderRequest.Refund oneUnit = request(fixture.sellerId(), fixture.orderId(), "refund-partial-key",
                itemId, 1);

        queueRefundStatus("PENDING");
        RefundResponse.Refund pendingRefund = refunds.refund(oneUnit);
        assertThat(pendingRefund.paymentStatus())
                .isEqualTo(OrderRefundPaymentStatus.PENDING);
        assertStock(fixture, 3, 2);
        assertThat(journalCountForRefund(pendingRefund.refundId())).isZero();

        RefundResponse.Refund partial = refunds.refund(oneUnit);
        assertThat(partial.paymentStatus())
                .isEqualTo(OrderRefundPaymentStatus.COMPLETED);

        assertThat(partial.refundId().version()).isEqualTo(7);
        assertThat(partial.refundAmountWon()).isEqualTo(125);
        assertThat(partial.status()).isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertStock(fixture, 4, 1);
        assertThat(entriesForRefund(partial.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 125),
                new Entry("SELLER_PAYABLE", "DEBIT", 125));
        assertThat(journalCountForRefund(partial.refundId())).isEqualTo(1);
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));

        assertThat(refunds.refund(oneUnit)).isEqualTo(partial);
        assertThatThrownBy(() -> refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-partial-key", itemId, 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThatThrownBy(() -> refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-over-limit-key", itemId, 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(fixture, 4, 1);
        assertThat(journalCountForRefund(partial.refundId())).isEqualTo(1);

        fulfillments.start(fixture.sellerId(), fixture.orderId());
        RefundResponse.Refund full = refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "refund-final-key", itemId, 1));

        assertThat(full.refundAmountWon()).isEqualTo(125);
        assertThat(full.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 250),
                new Entry("SELLER_PAYABLE", "CREDIT", 250));
        assertThatThrownBy(() -> createShipment(fixture.sellerId(), fixture.orderId(),
                List.of(new OrderRequest.Shipment.Item(fixture.orderItemId(), 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code())
                        .isEqualTo(OrderException.Code.CONFLICT));
        assertThat(journalCountForRefund(full.refundId())).isEqualTo(1);
    }

    @Test
    void unknownRefundRetainsQuantityUntilProviderGetConfirmsCompletion() {
        Fixture fixture = paidOrder(1, 2, 125);
        OrderRequest.Refund request = request(fixture.sellerId(), fixture.orderId(), "refund-unknown-key",
                fixture.orderItemId(), 1);

        queueRefundStatus("UNKNOWN");
        RefundResponse.Refund unknown = refunds.refund(request);
        assertThat(unknown.paymentStatus())
                .isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        assertStock(fixture, 1, 1);
        assertThat(journalCountForRefund(unknown.refundId())).isZero();

        RefundResponse.Refund completed = refunds.refund(request);
        assertThat(completed.paymentStatus())
                .isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertStock(fixture, 2, 0);
        assertThat(journalCountForRefund(completed.refundId())).isEqualTo(1);
        assertThat(refunds.refund(request)).isEqualTo(completed);
        assertStock(fixture, 2, 0);
    }

    @Test
    void lostRefundResponseStaysUnknownAndSameKeyReplayReusesProviderRequestId() {
        Fixture fixture = paidOrder(1, 2, 125);
        OrderRequest.Refund request = request(fixture.sellerId(), fixture.orderId(), "refund-lost-response-key",
                fixture.orderItemId(), 1);

        disconnectNextRefundResponse();
        RefundResponse.Refund unknown = refunds.refund(request);
        assertThat(unknown.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        assertStock(fixture, 1, 1);
        assertThat(journalCountForRefund(unknown.refundId())).isZero();
        assertThat(refundRequestIds()).hasSize(1);

        RefundResponse.Refund completed = refunds.refund(request);
        assertThat(completed.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertThat(completed.refundId()).isEqualTo(unknown.refundId());
        assertThat(refundRequestIds()).hasSize(2).containsOnly(refundRequestIds().getFirst());
        assertStock(fixture, 2, 0);
        assertThat(journalCountForRefund(completed.refundId())).isEqualTo(1);

        assertThat(refunds.refund(request)).isEqualTo(completed);
        assertThat(refundRequestIds()).hasSize(2);
        assertStock(fixture, 2, 0);
        assertThat(journalCountForRefund(completed.refundId())).isEqualTo(1);
    }

    /**
     * EXP-119: PayPal은 환불 키를 45일 기억한다. 그 뒤 같은 키로 부분 환불을 다시 보내면 PayPal은 새 환불로 받아
     * 돈이 두 번 나간다. 키가 그만큼 오래된 결과 불명 환불은 다시 보내지 않는다.
     */
    @Test
    void unknownRefundOlderThanPayPalRequestIdRetentionIsNotResent() {
        Fixture fixture = paidOrder(2, 3, 125);
        OrderRequest.Refund request = request(fixture.sellerId(), fixture.orderId(), "refund-stale-key",
                fixture.orderItemId(), 1);
        String captureId = jdbc.queryForObject(
                "SELECT provider_capture_id FROM payment_attempt WHERE attempt_id = ?",
                String.class, fixture.paymentAttemptId());
        String refundPost = "POST /v2/payments/captures/" + captureId + "/refund";

        disconnectNextRefundResponse();
        RefundResponse.Refund unknown = refunds.refund(request);
        assertThat(unknown.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        BigDecimal firstRefund = refundedTotal(captureId);
        assertThat(firstRefund).isPositive();
        jdbc.update("UPDATE payment_refund_operation SET created_at = created_at - INTERVAL '46 days'"
                + " WHERE provider_request_id = ?::uuid", refundRequestIds().getFirst());
        forgetRefundRequestIds();

        RefundResponse.Refund retried = refunds.refund(request);

        assertThat(refundedTotal(captureId)).isEqualByComparingTo(firstRefund);
        assertThat(providerCalls().get(refundPost)).isEqualTo(1);
        assertThat(retried.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        assertThat(journalCountForRefund(retried.refundId())).isZero();
    }

    @Test
    void inspectedReturnResponseExposesUnknownAndReconciledProviderRefundStatus() {
        Fixture fixture = paidOrder(1, 1, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn created = returns.request(returnRequest(buyerRef, fixture.orderId(), "return-status-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1)));
        returns.receive(fixture.sellerId(), fixture.orderId(), created.returnId());
        OrderRequest.ReturnInspection inspection = new OrderRequest.ReturnInspection(List.of(
                new OrderRequest.ReturnInspection.Item(fixture.orderItemId(), OrderRequest.ReturnInspection.Result.PASSED)));

        queueRefundStatus("UNKNOWN");
        ReturnResponse.OrderReturn unknown = returns.inspect(fixture.sellerId(), fixture.orderId(), created.returnId(),
                inspection);
        assertThat(unknown.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(unknown.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        assertThat(unknown.items().get(0).refundId()).isNotNull();

        RefundResponse.Refund completed = refunds.refund(request(fixture.sellerId(), fixture.orderId(),
                "return-" + created.returnId(), fixture.orderItemId(), 1));
        ReturnResponse.OrderReturn refreshed = returns.find(buyerRef, fixture.orderId(), created.returnId());
        assertThat(completed.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertThat(refreshed.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(refreshed.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertThat(refreshed.items().get(0).refundId()).isEqualTo(unknown.items().get(0).refundId());
        assertThat(journalCountForRefund(completed.refundId())).isEqualTo(1);
    }

    @Test
    void refundSummaryAndFulfillmentGateAggregateAcrossOrderItems() {
        MultiItemFixture fixture = paidOrderWithTwoItems();

        RefundResponse.Refund firstRefund = refunds.refund(new OrderRequest.Refund(
                fixture.sellerId(), fixture.orderId(), "multi-item-first-refund",
                List.of(new OrderRequest.Refund.Item(fixture.firstItemId(), 1))));

        assertThat(firstRefund.status()).isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.PARTIALLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(100);
        assertPool(fixture.sellerId(), fixture.firstSkuId(), 1, 0, 0, 1);
        assertPool(fixture.sellerId(), fixture.secondSkuId(), 0, 0, 1, 1);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(fulfillments.start(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(entriesForRefund(firstRefund.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 100),
                new Entry("SELLER_PAYABLE", "DEBIT", 100));

        RefundResponse.Refund secondRefund = refunds.refund(new OrderRequest.Refund(
                fixture.sellerId(), fixture.orderId(), "multi-item-second-refund",
                List.of(new OrderRequest.Refund.Item(fixture.secondItemId(), 1))));

        assertThat(secondRefund.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(350);
        assertPool(fixture.sellerId(), fixture.firstSkuId(), 1, 0, 0, 1);
        assertPool(fixture.sellerId(), fixture.secondSkuId(), 1, 0, 0, 1);
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(entriesForRefund(secondRefund.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 250),
                new Entry("SELLER_PAYABLE", "DEBIT", 250));
        assertThat(entriesForAttempt(fixture.paymentAttemptId())).containsExactly(
                new Entry("PSP_CLEARING", "DEBIT", 350),
                new Entry("SELLER_PAYABLE", "CREDIT", 350));
        assertThatThrownBy(() -> createShipment(fixture.sellerId(), fixture.orderId(), List.of(
                new OrderRequest.Shipment.Item(fixture.firstItemId(), 1),
                new OrderRequest.Shipment.Item(fixture.secondItemId(), 1))))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
    }

    @Test
    void rejectsUnpaidOrdersOverRefundsAndRefundsAfterShipmentWithoutChangingStock() {
        Fixture unpaid = unpaidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(request(unpaid.sellerId(), unpaid.orderId(),
                "unpaid-refund-key", unpaid.orderItemId(), 1)))
                .isInstanceOf(OrderException.class);
        assertThat(refunds.find(unpaid.sellerId(), unpaid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(unpaid, 4, 1, 0);

        Fixture paid = paidOrder(1, 5, 200);
        assertThatThrownBy(() -> refunds.refund(request(paid.sellerId(), paid.orderId(),
                "over-refund-key", paid.orderItemId(), 2)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();

        fulfillments.start(paid.sellerId(), paid.orderId());
        createShipment(paid.sellerId(), paid.orderId(), List.of(
                new OrderRequest.Shipment.Item(paid.orderItemId(), 1)));
        assertThatThrownBy(() -> refunds.refund(request(paid.sellerId(), paid.orderId(),
                "shipped-refund-key", paid.orderItemId(), 1)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code()).isEqualTo(OrderException.Code.CONFLICT));
        assertThat(refunds.find(paid.sellerId(), paid.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(paid, 4, 1);
        assertThat(journalCountForOrder(paid.orderId())).isZero();
    }

    @Test
    void concurrentRetryCreatesOneRefundAndRestoresStockOnce() throws Exception {
        Fixture fixture = paidOrder(1, 5, 300);
        OrderRequest.Refund request = request(fixture.sellerId(), fixture.orderId(),
                "concurrent-refund-key", fixture.orderItemId(), 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RefundResponse.Refund> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(request);
            });
            Future<RefundResponse.Refund> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return refunds.refund(request);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RefundResponse.Refund one = first.get(10, TimeUnit.SECONDS);
            RefundResponse.Refund two = second.get(10, TimeUnit.SECONDS);
            assertThat(one).isEqualTo(two);
            assertStock(fixture, 5, 0);
            assertThat(journalCountForRefund(one.refundId())).isEqualTo(1);
            assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDeliveredReturnRequestsCannotClaimSameUnitsTwice() throws Exception {
        Fixture fixture = paidOrder(2, 2, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        OrderRequest.Return firstRequest = returnRequest(buyerRef, fixture.orderId(), "concurrent-return-one",
                new OrderRequest.Return.Item(fixture.orderItemId(), 2));
        OrderRequest.Return secondRequest = returnRequest(buyerRef, fixture.orderId(), "concurrent-return-two",
                new OrderRequest.Return.Item(fixture.orderItemId(), 2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ReturnResponse.OrderReturn> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.request(firstRequest);
            });
            Future<ReturnResponse.OrderReturn> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.request(secondRequest);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ReturnResponse.OrderReturn accepted = null;
            int conflicts = 0;
            try {
                accepted = first.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(OrderException.class)
                        .satisfies(error -> assertThat(((OrderException) error).code())
                                .isEqualTo(OrderException.Code.CONFLICT));
                conflicts++;
            }
            try {
                accepted = second.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(OrderException.class)
                        .satisfies(error -> assertThat(((OrderException) error).code())
                                .isEqualTo(OrderException.Code.CONFLICT));
                conflicts++;
            }
            assertThat(accepted).isNotNull();
            assertThat(conflicts).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_return WHERE order_id = ?",
                Integer.class, fixture.orderId())).isEqualTo(1);
        long acceptedQuantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(item.quantity), 0)
                FROM order_return_item item
                JOIN order_return return_record ON return_record.return_id = item.return_id
                WHERE return_record.order_id = ?
                """, Long.class, fixture.orderId());
        assertThat(acceptedQuantity).isLessThanOrEqualTo(2L).isEqualTo(2L);
        ReturnResponse.OrderReturn persisted = returns.find(buyerRef, fixture.orderId(),
                jdbc.queryForObject("SELECT return_id FROM order_return WHERE order_id = ?",
                        UUID.class, fixture.orderId()));
        assertThat(persisted.status()).isEqualTo(OrderReturnStatus.REQUESTED);
        assertThat(persisted.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(fixture.orderItemId());
            assertThat(item.quantity()).isEqualTo(2);
        });
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refundRequestCount(fixture.orderId())).isZero();
        assertThat(journalCountForOrder(fixture.orderId())).isZero();
        assertPool(fixture.sellerId(), fixture.skuId(), 0, 0, 2, 2);
    }

    @Test
    void concurrentIdenticalInspectionRecordsOneRefundAndRestoresStockOnce() throws Exception {
        Fixture fixture = paidOrder(2, 2, 125);
        deliver(fixture);
        String buyerRef = buyerRef(fixture.orderId());
        ReturnResponse.OrderReturn requested = returns.request(returnRequest(buyerRef, fixture.orderId(),
                "concurrent-inspection-return", new OrderRequest.Return.Item(fixture.orderItemId(), 2)));
        returns.receive(fixture.sellerId(), fixture.orderId(), requested.returnId());
        OrderRequest.ReturnInspection passed = new OrderRequest.ReturnInspection(List.of(
                new OrderRequest.ReturnInspection.Item(fixture.orderItemId(),
                        OrderRequest.ReturnInspection.Result.PASSED)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ReturnResponse.OrderReturn firstResult;
        ReturnResponse.OrderReturn secondResult;
        try {
            Future<ReturnResponse.OrderReturn> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.inspect(fixture.sellerId(), fixture.orderId(), requested.returnId(), passed);
            });
            Future<ReturnResponse.OrderReturn> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return returns.inspect(fixture.sellerId(), fixture.orderId(), requested.returnId(), passed);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult = first.get(10, TimeUnit.SECONDS);
            secondResult = second.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(firstResult).isEqualTo(secondResult);
        ReturnResponse.OrderReturn persisted = returns.find(buyerRef, fixture.orderId(), requested.returnId());
        assertThat(persisted.status()).isEqualTo(OrderReturnStatus.REFUND_RECORDED);
        assertThat(persisted.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(fixture.orderItemId());
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.refundId()).isNotNull();
            assertThat(item.refundAmountWon()).isEqualTo(250L);
        });
        UUID refundId = persisted.items().get(0).refundId();
        assertThat(orders.find(fixture.orderId()).state()).isEqualTo(OrderState.PAID);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status())
                .isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).totalRecordedAmountWon()).isEqualTo(250L);
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        long refundedQuantity = jdbc.queryForObject("""
                SELECT COALESCE(SUM(item.quantity), 0)
                FROM order_refund_request_item item
                JOIN order_refund_request request ON request.refund_id = item.refund_id
                WHERE request.order_id = ?
                """, Long.class, fixture.orderId());
        assertThat(refundedQuantity).isEqualTo(2L);
        assertThat(journalCountForOrder(fixture.orderId())).isEqualTo(1);
        assertThat(journalCountForRefund(refundId)).isEqualTo(1);
        List<Entry> refundEntries = entriesForRefund(refundId);
        assertThat(refundEntries).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 250),
                new Entry("SELLER_PAYABLE", "DEBIT", 250));
        assertThat(refundEntries.stream().filter(entry -> entry.side().equals("DEBIT"))
                .mapToLong(Entry::recordedAmountWon).sum()).isEqualTo(250L);
        assertThat(refundEntries.stream().filter(entry -> entry.side().equals("CREDIT"))
                .mapToLong(Entry::recordedAmountWon).sum()).isEqualTo(250L);
        assertPool(fixture.sellerId(), fixture.skuId(), 2, 0, 0, 2);
    }

    @Test
    void sellerRefundHttpEndpointsExposeLocalRefundAndAggregateStatus() throws Exception {
        Fixture fixture = paidOrder(3, 3, 200);
        String refundBody = """
                {"items":[{"orderItemId":"%s","quantity":1}]}
                """.formatted(fixture.orderItemId());

        String originalResponse = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "http-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmountWon").value(200))
                .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECORDED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String replayedResponse = mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "http-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(replayedResponse).isEqualTo(originalResponse);

        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "http-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":2}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(post("/api/sellers/{sellerId}/orders/{orderId}/refund-records",
                        fixture.sellerId(), fixture.orderId())
                        .header("Idempotency-Key", "http-refund-invalid-quantity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"orderItemId":"%s","quantity":0}]}
                                """.formatted(fixture.orderItemId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/sellers/{sellerId}/orders/{orderId}/refund-record",
                        fixture.sellerId(), fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECORDED"))
                .andExpect(jsonPath("$.totalRecordedAmountWon").value(200));
    }

    /** Seller 계정 정지가 판매와 결제를 막고, 이미 결제된 주문은 운영자가 Seller 환불 경로로 돌려준다(ADR-0112). */
    @Test
    void suspendedSellerStopsSellingAndOperatorRefundsPaidOrderThroughSellerRefundPath() throws Exception {
        Fixture paid = paidOrder(2, 5, 125);
        UUID accountId = accountRepository.createAccount("suspended-seller-" + UUID.randomUUID() + "@example.com",
                "not-a-login-hash").orElseThrow();
        accountRepository.verifyEmail(accountId, Instant.now());
        accountService.linkSeller(accountId, paid.sellerId());
        String cartBuyer = "suspended-cart-" + UUID.randomUUID();
        carts.setItemQuantity(paid.sellerId(), cartBuyer, paid.skuId(), 1);
        String checkoutBuyer = "suspended-checkout-" + UUID.randomUUID();
        carts.setItemQuantity(paid.sellerId(), checkoutBuyer, paid.skuId(), 1);
        CheckoutResponse reservedBeforeSuspension = checkouts.checkout(checkoutBuyer, "before-" + UUID.randomUUID());
        String refundPath = "/api/internal/accounts/{accountId}/sellers/{sellerId}/orders/{orderId}/refund";

        accountService.suspendAccount(accountId, "fraud review", "operator-7", "suspend-" + UUID.randomUUID());

        assertThatThrownBy(() -> checkouts.checkout(cartBuyer, "after-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(CatalogException.class, error ->
                        assertThat(error.code()).isEqualTo(CatalogException.Code.PRODUCT_NOT_ORDERABLE));
        assertThatThrownBy(() -> checkoutPayments.submit(reservedBeforeSuspension.checkoutId()))
                .isInstanceOfSatisfying(OrderException.class, error ->
                        assertThat(error.code()).isEqualTo(OrderException.Code.CONFLICT));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(refundPath, accountId, paid.sellerId(), paid.orderId())
                            .principal(() -> "operator-7").header("Idempotency-Key", "operator-refund-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refundAmountWon").value(250))
                    .andExpect(jsonPath("$.status").value("FULLY_RECORDED"))
                    .andExpect(jsonPath("$.paymentStatus").value("COMPLETED"));
        }
        assertThat(refundRequestCount(paid.orderId())).isEqualTo(1);
        assertStock(paid, 4, 1, 0);
        assertThat(jdbc.queryForList("""
                SELECT actor FROM domain_audit_event
                WHERE aggregate_type = 'order' AND aggregate_id = ? AND event_type = 'ORDER_REFUND_BY_OPERATOR'
                """, String.class, paid.orderId().toString())).containsExactly("operator-7");
    }

    @Test
    void settlementFailureRollsBackRefundStateInventoryAndReversalBeforeRetry() {
        Fixture fixture = paidOrder(1, 5, 450);
        OrderRequest.Refund request = request(fixture.sellerId(), fixture.orderId(),
                "rollback-refund-key", fixture.orderItemId(), 1);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_refund_settlement_entry() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.account_code = 'PSP_CLEARING' AND NEW.entry_side = 'CREDIT' THEN
                        RAISE EXCEPTION 'simulated local refund posting failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_refund_settlement_entry_trigger
                BEFORE INSERT ON settlement_entry
                FOR EACH ROW EXECUTE FUNCTION fail_refund_settlement_entry()
                """);
        try {
            assertThatThrownBy(() -> refunds.refund(request))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_refund_settlement_entry_trigger ON settlement_entry");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_refund_settlement_entry()");
        }

        assertThat(refunds.find(fixture.sellerId(), fixture.orderId()).status()).isEqualTo(OrderRefundStatus.NONE);
        assertStock(fixture, 4, 1);
        assertThat(refundRequestCount(fixture.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payment_refund_status FROM order_refund_request WHERE idempotency_key = ?",
                String.class, request.idempotencyKey())).isEqualTo("PENDING");
        assertThat(journalCountForOrder(fixture.orderId())).isZero();

        RefundResponse.Refund recovered = refunds.refund(request);
        assertThat(recovered.status()).isEqualTo(OrderRefundStatus.FULLY_RECORDED);
        assertStock(fixture, 5, 0);
        assertThat(journalCountForRefund(recovered.refundId())).isEqualTo(1);
        assertThat(entriesForRefund(recovered.refundId())).containsExactly(
                new Entry("PSP_CLEARING", "CREDIT", 450),
                new Entry("SELLER_PAYABLE", "DEBIT", 450));
    }

    private String buyerRef(UUID orderId) {
        return jdbc.queryForObject("SELECT buyer_ref FROM purchase_order WHERE order_id = ?", String.class, orderId);
    }

    @Test
    void buyerCanListReturnsForAnOrderWithoutKnowingReturnIds() {
        // 반품 요청 응답에는 returnId 가 있지만, 앱을 다시 열거나 기록을 잃으면 조회 수단이
        // 없었다. 구매자는 진행 중인 반품 상태를 목록으로 확인할 수 있어야 한다.
        Fixture fixture = paidOrder(1, 2, 125);
        String buyerRef = buyerRef(fixture.orderId());
        deliver(fixture);
        ReturnResponse.OrderReturn created = returns.request(returnRequest(buyerRef, fixture.orderId(),
                "return-list-key", new OrderRequest.Return.Item(fixture.orderItemId(), 1)));

        List<ReturnResponse.OrderReturn> listed = returns.findForBuyer(buyerRef, fixture.orderId());

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().returnId()).isEqualTo(created.returnId());
        assertThat(listed.getFirst().status()).isEqualTo(OrderReturnStatus.REQUESTED);
    }

    @Test
    void buyerReturnListEndpointReturnsRequestedReturns() throws Exception {
        Fixture fixture = paidOrder(1, 2, 125);
        String buyerRef = buyerRef(fixture.orderId());
        deliver(fixture);
        returns.request(returnRequest(buyerRef, fixture.orderId(), "return-http-key",
                new OrderRequest.Return.Item(fixture.orderItemId(), 1)));

        mockMvc.perform(get("/api/buyers/{buyerRef}/orders/{orderId}/returns", buyerRef, fixture.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$[0].returnId").isNotEmpty());
    }

    @Test
    void buyerCannotListAnotherBuyersReturns() {
        Fixture fixture = paidOrder(1, 2, 125);
        deliver(fixture);
        returns.request(returnRequest(buyerRef(fixture.orderId()), fixture.orderId(),
                "return-other-key", new OrderRequest.Return.Item(fixture.orderItemId(), 1)));

        // 존재 여부를 노출하지 않도록 소유하지 않은 주문은 NOT_FOUND 다.
        assertThatThrownBy(() -> returns.findForBuyer("someone-else", fixture.orderId()))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo(OrderException.Code.NOT_FOUND));
    }

    @Test
    void returnListIsEmptyWhenNoReturnWasRequested() {
        Fixture fixture = paidOrder(1, 2, 125);
        deliver(fixture);

        assertThat(returns.findForBuyer(buyerRef(fixture.orderId()), fixture.orderId())).isEmpty();
    }

    private OrderRequest.Return returnRequest(String buyerRef, UUID orderId, String key, OrderRequest.Return.Item item) {
        return new OrderRequest.Return(buyerRef, orderId, key, List.of(item), null, null);
    }

    private void deliver(Fixture fixture) {
        deliver(fixture.sellerId(), fixture.orderId());
    }

    private void deliver(UUID sellerId, UUID orderId) {
        fulfillments.start(sellerId, orderId);
        List<ShipmentItem> items = jdbc.query("""
                SELECT oi.order_item_id,
                       oi.quantity - COALESCE((
                           SELECT SUM(refund_item.quantity)
                           FROM order_refund_request_item refund_item
                           JOIN order_refund_request refund ON refund.refund_id = refund_item.refund_id
                           WHERE refund.order_id = oi.order_id
                             AND refund_item.order_item_id = oi.order_item_id
                             AND NOT EXISTS (
                                 SELECT 1 FROM order_return_item return_item
                                 WHERE return_item.refund_id = refund.refund_id
                                   AND return_item.order_item_id = oi.order_item_id
                             )
                       ), 0) AS quantity
                FROM order_item oi
                WHERE oi.order_id = ?
                  AND oi.quantity > 0
                """, (result, rowNum) -> new ShipmentItem(
                UUID.fromString(result.getString("order_item_id")), result.getInt("quantity")), orderId);
        for (ShipmentItem item : items) {
            if (item.quantity() <= 0) {
                continue;
            }
            OrderViewResponse.Shipment shipment = createShipment(sellerId, orderId,
                    List.of(new OrderRequest.Shipment.Item(item.orderItemId(), item.quantity())));
            shipments.deliver(sellerId, orderId, shipment.shipmentId());
        }
    }

    private OrderViewResponse.Shipment createShipment(
            UUID sellerId, UUID orderId, List<OrderRequest.Shipment.Item> items) {
        return shipments.create(new OrderRequest.Shipment(sellerId, orderId,
                "test-shipment-" + UUID.randomUUID(), null, null, items));
    }

    private record ShipmentItem(UUID orderItemId, int quantity) {
    }

    private MultiItemFixture paidOrderWithTwoItems() {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Multi-item refund test", null));
        String firstSkuId = "multi-refund-a-" + UUID.randomUUID();
        String secondSkuId = "multi-refund-b-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(firstSkuId, 100, 1));
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(secondSkuId, 250, 1));
        String buyerRef = "multi-refund-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, firstSkuId, 1);
        carts.setItemQuantity(sellerId, buyerRef, secondSkuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "multi-refund-checkout-" + UUID.randomUUID());
        OrderViewResponse.Order reserved = checkout.orders().get(0);
        UUID firstItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), firstSkuId);
        UUID secondItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ? AND sku_id = ?",
                UUID.class, reserved.orderId(), secondSkuId);
        OrderViewResponse.Order paid = completePayPalCheckout(checkout.checkoutId()).orders().get(0);
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        return new MultiItemFixture(sellerId, paid.orderId(), firstItemId, firstSkuId, secondItemId, secondSkuId,
                paid.paymentAttemptId());
    }

    private void assertPool(UUID sellerId, String skuId, int available, int reserved, int claimed,
            int initialCapacity) {
        InventoryResponse.Pool pool = inventory.find(sellerId, skuId);
        assertThat(pool.available()).isEqualTo(available);
        assertThat(pool.reserved()).isEqualTo(reserved);
        assertThat(pool.claimed()).isEqualTo(claimed);
        assertThat(pool.available() + pool.reserved() + pool.claimed()).isEqualTo(initialCapacity);
    }

    private Fixture paidOrder(int quantity, int initialStock, long unitPriceWon) {
        Fixture fixture = createOrder(quantity, initialStock, unitPriceWon);
        OrderViewResponse.Order paid = completePayPalCheckout(fixture.checkoutId()).orders().get(0);
        assertThat(paid.state()).isEqualTo(OrderState.PAID);
        return new Fixture(fixture.sellerId(), fixture.orderId(), fixture.orderItemId(), fixture.skuId(),
                fixture.checkoutId(), paid.paymentAttemptId());
    }

    private CheckoutResponse completePayPalCheckout(UUID checkoutId) {
        CheckoutResponse pending = checkoutPayments.submit(checkoutId);
        return checkoutPayments.completeApproval(checkoutId, approvalToken(pending.paymentApprovalUrl()));
    }

    private Fixture unpaidOrder(int quantity, int initialStock, long unitPriceWon) {
        return createOrder(quantity, initialStock, unitPriceWon);
    }

    private Fixture createOrder(int quantity, int initialStock, long unitPriceWon) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Refund test product", null));
        String skuId = "refund-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(),
                new ProductRequest.RegisterSellerSku(skuId, unitPriceWon, initialStock));
        String buyerRef = "refund-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, quantity);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "refund-checkout-" + UUID.randomUUID());
        OrderViewResponse.Order order = checkout.orders().get(0);
        UUID orderItemId = jdbc.queryForObject(
                "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, order.orderId());
        return new Fixture(sellerId, order.orderId(), orderItemId, skuId, checkout.checkoutId(), null);
    }

    private static OrderRequest.Refund request(UUID sellerId, UUID orderId, String idempotencyKey,
            UUID orderItemId, int quantity) {
        return new OrderRequest.Refund(sellerId, orderId, idempotencyKey,
                List.of(new OrderRequest.Refund.Item(orderItemId, quantity)));
    }

    private void assertStock(Fixture fixture, int available, int claimed) {
        InventoryResponse.Pool stock = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(stock.available()).isEqualTo(available);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.claimed()).isEqualTo(claimed);
    }

    private void assertStock(Fixture fixture, int available, int reserved, int claimed) {
        InventoryResponse.Pool stock = inventory.find(fixture.sellerId(), fixture.skuId());
        assertThat(stock.available()).isEqualTo(available);
        assertThat(stock.reserved()).isEqualTo(reserved);
        assertThat(stock.claimed()).isEqualTo(claimed);
    }

    private int journalCountForRefund(UUID refundId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_refund_id = ?",
                Integer.class, refundId);
    }

    private int journalCountForOrder(UUID orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_order_id = ? AND source_refund_id IS NOT NULL",
                Integer.class, orderId);
    }

    private int refundRequestCount(UUID orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_request WHERE order_id = ?",
                Integer.class, orderId);
    }

    private List<Entry> entriesForRefund(UUID refundId) {
        return jdbc.query("""
                SELECT entry.account_code, entry.entry_side, entry.amount_won
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.source_refund_id = ?
                ORDER BY entry.account_code
                """, (resultSet, rowNum) -> new Entry(resultSet.getString("account_code"),
                resultSet.getString("entry_side"), resultSet.getLong("amount_won")), refundId);
    }

    private List<Entry> entriesForAttempt(UUID attemptId) {
        return jdbc.query("""
                SELECT entry.account_code, entry.entry_side, entry.amount_won
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.source_attempt_id = ?
                ORDER BY entry.account_code
                """, (resultSet, rowNum) -> new Entry(resultSet.getString("account_code"),
                resultSet.getString("entry_side"), resultSet.getLong("amount_won")), attemptId);
    }

    private record MultiItemFixture(UUID sellerId, UUID orderId, UUID firstItemId, String firstSkuId,
            UUID secondItemId, String secondSkuId, UUID paymentAttemptId) {
    }

    private record Fixture(UUID sellerId, UUID orderId, UUID orderItemId, String skuId, UUID checkoutId, UUID paymentAttemptId) {
    }

    private record Entry(String accountCode, String side, long recordedAmountWon) {
    }

    @Test
    void refundIdempotencyKeyIsScopedToOrderSoOtherSellersCanReuseIt() {
        String sharedKey = "refund-1";

        // 서로 다른 checkout 의 두 판매자가 같은 key 를 써도 둘 다 환불된다(예전에는 전역 UNIQUE 로 409).
        Fixture first = paidOrder(1, 2, 125);
        Fixture second = paidOrder(1, 2, 300);
        RefundResponse.Refund firstRefund = refunds.refund(request(first.sellerId(), first.orderId(), sharedKey,
                first.orderItemId(), 1));
        RefundResponse.Refund secondRefund = refunds.refund(request(second.sellerId(), second.orderId(), sharedKey,
                second.orderItemId(), 1));
        assertThat(firstRefund.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertThat(secondRefund.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        assertThat(secondRefund.refundId()).isNotEqualTo(firstRefund.refundId());
        assertThat(secondRefund.refundAmountWon()).isEqualTo(300);
        assertThat(refundRequestCount(first.orderId())).isEqualTo(1);
        assertThat(refundRequestCount(second.orderId())).isEqualTo(1);
        assertStock(first, 2, 0);
        assertStock(second, 2, 0);

        // 같은 checkout 의 두 판매자 주문은 결제 attempt 하나를 공유한다. 같은 key 를 써도 payment
        // 계층에서 서로 다른 송금 작업이 되어야 한다.
        UUID sellerA = sellers.createSeller().sellerId();
        UUID sellerB = sellers.createSeller().sellerId();
        String skuA = "refund-scope-a-" + UUID.randomUUID();
        String skuB = "refund-scope-b-" + UUID.randomUUID();
        products.registerSku(sellerA, products.createProduct(sellerA,
                new ProductRequest.CreateProduct("Refund scope A", null)).productId(),
                new ProductRequest.RegisterSellerSku(skuA, 125, 2));
        products.registerSku(sellerB, products.createProduct(sellerB,
                new ProductRequest.CreateProduct("Refund scope B", null)).productId(),
                new ProductRequest.RegisterSellerSku(skuB, 125, 2));
        String buyerRef = "refund-scope-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerA, buyerRef, skuA, 1);
        carts.setItemQuantity(sellerB, buyerRef, skuB, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "refund-scope-checkout-" + UUID.randomUUID());
        assertThat(checkout.orders()).hasSize(2);
        List<OrderViewResponse.Order> paidOrders = completePayPalCheckout(checkout.checkoutId()).orders();
        assertThat(paidOrders).hasSize(2);
        for (OrderViewResponse.Order paid : paidOrders) {
            assertThat(paid.state()).isEqualTo(OrderState.PAID);
            UUID orderItemId = jdbc.queryForObject(
                    "SELECT order_item_id FROM order_item WHERE order_id = ?", UUID.class, paid.orderId());
            UUID sellerId = jdbc.queryForObject(
                    "SELECT seller_id FROM purchase_order WHERE order_id = ?", UUID.class, paid.orderId());
            RefundResponse.Refund refund = refunds.refund(request(sellerId, paid.orderId(), sharedKey,
                    orderItemId, 1));
            assertThat(refund.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
            assertThat(refund.refundAmountWon()).isEqualTo(125);
        }
        UUID sharedAttemptId = paidOrders.get(0).paymentAttemptId();
        assertThat(paidOrders.get(1).paymentAttemptId()).isEqualTo(sharedAttemptId);
        assertThat(jdbc.queryForList(
                "SELECT idempotency_key FROM payment_refund_operation WHERE attempt_id = ?",
                String.class, sharedAttemptId))
                .containsExactlyInAnyOrder(
                        "order:" + paidOrders.get(0).orderId() + ":" + sharedKey,
                        "order:" + paidOrders.get(1).orderId() + ":" + sharedKey);

        // 같은 주문 안에서 같은 key 로 다른 품목을 보내면 여전히 CONFLICT 다.
        MultiItemFixture multi = paidOrderWithTwoItems();
        RefundResponse.Refund multiRefund = refunds.refund(request(multi.sellerId(), multi.orderId(), sharedKey,
                multi.firstItemId(), 1));
        assertThat(refunds.refund(request(multi.sellerId(), multi.orderId(), sharedKey,
                multi.firstItemId(), 1))).isEqualTo(multiRefund);
        assertThatThrownBy(() -> refunds.refund(request(multi.sellerId(), multi.orderId(), sharedKey,
                multi.secondItemId(), 1)))
                .isInstanceOf(OrderException.class)
                .satisfies(error -> assertThat(((OrderException) error).code())
                        .isEqualTo(OrderException.Code.CONFLICT));
        assertThat(refundRequestCount(multi.orderId())).isEqualTo(1);
    }
}
