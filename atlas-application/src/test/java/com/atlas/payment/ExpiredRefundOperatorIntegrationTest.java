package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.dto.request.OrderRequest;
import com.atlas.order.dto.response.CheckoutResponse;
import com.atlas.order.dto.response.OrderViewResponse;
import com.atlas.order.dto.response.RefundResponse;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderState;
import com.atlas.order.service.CartCheckoutService;
import com.atlas.order.service.CartService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.bastiaanjansen.otp.TOTPGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 첫 요청 뒤 30일이 지나 PayPal에 다시 보내지 않는 결과 불명 환불(ADR-0108)을 운영자가 PayPal 확인 결과로 닫는 경로를
 * 실제 PostgreSQL(V7 원장 균형·append-only 트리거 포함)과 지급 운영자 인증 경계(account JWT + MFA)로 확인한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ExpiredRefundOperatorIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String PASSWORD = "long-enough-password-123";
    private static final String NOT_FOUND = "{\"outcome\":\"FAILED\",\"providerRefundNotFound\":true}";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.payment.paypal.payouts.enabled", () -> "true");
        registry.add("atlas.payment.paypal.payouts.recipient-encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("atlas.settlement.payout.operator.enabled", () -> "true");
        registry.add("atlas.account.mfa.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;
    @Autowired private CheckoutPaymentService checkoutPayments;
    @Autowired private OrderRefundService refunds;

    private final AtomicInteger origins = new AtomicInteger();

    @Test
    void completedResolutionPostsTheRefundOnceAndClosesTheReviewJob() throws Exception {
        Paid paid = paidOrder(10_000);
        Stuck stuck = stuckRefund(paid, "stuck-completed");
        String paypalRefundId = stubRefundIdForRequest(stuck.providerRequestId());
        long before = sellerHeldWon(paid.sellerId());
        Operator operator = operator();

        MockHttpServletResponse first = resolve(operator, stuck, completed(paypalRefundId));
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(field(first, "paymentStatus")).isEqualTo("COMPLETED");
        assertThat(resolve(operator, stuck, completed(paypalRefundId)).getStatus()).as("같은 확정의 재요청").isEqualTo(200);
        assertThat(resolve(operator, stuck, NOT_FOUND).getStatus()).as("이미 닫은 환불을 다른 결과로").isEqualTo(409);

        assertThat(refundJournals(stuck.refundId())).isEqualTo(1);
        assertThat(sellerHeldWon(paid.sellerId())).isEqualTo(before - stuck.amountWon());
        assertThat(jobStatus(stuck)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("""
                SELECT actor, payload->>'outcome' AS outcome, payload->>'evidence' AS evidence
                FROM domain_audit_event
                WHERE aggregate_type = 'payment_refund_operation' AND aggregate_id = ?
                """, stuck.operationId().toString()))
                .containsExactly(Map.of("actor", operator.accountId().toString(), "outcome", "COMPLETED",
                        "evidence", paypalRefundId));
    }

    @Test
    void notFoundResolutionReleasesTheReservationWithoutPosting() throws Exception {
        Paid paid = paidOrder(10_000);
        Stuck stuck = stuckRefund(paid, "stuck-failed");
        long before = sellerHeldWon(paid.sellerId());

        MockHttpServletResponse response = resolve(operator(), stuck, NOT_FOUND);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(field(response, "paymentStatus")).isEqualTo("FAILED");
        assertThat(refundJournals(stuck.refundId())).isZero();
        assertThat(sellerHeldWon(paid.sellerId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_refund_reservation WHERE refund_id = ?",
                String.class, stuck.refundId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT payment_refund_status FROM order_refund_request WHERE refund_id = ?",
                String.class, stuck.refundId())).isEqualTo("FAILED");
        assertThat(jobStatus(stuck)).isEqualTo("COMPLETED");
    }

    @Test
    void onlyExpiredRefundsWithConsistentUnusedEvidenceCanBeResolved() throws Exception {
        Operator operator = operator();
        Stuck young = unknownRefund(paidOrder(10_000), "young");
        assertThat(resolve(operator, young, NOT_FOUND).getStatus()).as("재전송 기한 안의 환불").isEqualTo(409);

        Stuck stuck = stuckRefund(paidOrder(10_000), "evidence");
        assertThat(resolve(operator, stuck, "{\"outcome\":\"COMPLETED\"}").getStatus()).as("증거 없음").isEqualTo(400);
        assertThat(resolve(operator, stuck, "{\"outcome\":\"COMPLETED\",\"providerRefundNotFound\":true}").getStatus())
                .as("완료에 refund id 없음").isEqualTo(400);
        assertThat(resolve(operator, stuck, "{\"outcome\":\"FAILED\",\"providerRefundId\":\"REFUND-1\"}").getStatus())
                .as("실패에 refund id").isEqualTo(400);

        // PayPal 쪽 환불로 이미 원장에 들어간 id(같은 id 공간의 역전 포함)와 다른 우리 환불이 받은 id.
        String providerOriginated = "DASH-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_provider_adjustment(adjustment_id, kind, provider_resource_id, status, review_reason,
                    first_event_id, last_event_id, created_at, updated_at)
                VALUES (?, 'REFUND', ?, 'REVIEW', 'MULTI_SELLER_CAPTURE', 'WH-1', 'WH-1', now(), now())
                """, UUID.randomUUID(), providerOriginated);
        assertThat(resolve(operator, stuck, completed(providerOriginated)).getStatus()).isEqualTo(409);
        String ours = completedRefundId(paidOrder(10_000));
        assertThat(resolve(operator, stuck, completed(ours)).getStatus()).isEqualTo(409);

        assertThat(jdbc.queryForObject("SELECT refund_state FROM payment_refund_operation WHERE refund_operation_id = ?",
                String.class, stuck.operationId())).isEqualTo("UNKNOWN");
        assertThat(refundJournals(stuck.refundId())).isZero();
    }

    @Test
    void resolutionRequiresAnMfaOperatorSession() throws Exception {
        Stuck stuck = stuckRefund(paidOrder(10_000), "auth");
        assertThat(mockMvc.perform(post(path(stuck)).contentType(MediaType.APPLICATION_JSON).content(NOT_FOUND))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        UUID withoutMfa = newAccount();
        grant(withoutMfa);
        assertThat(resolve(session(withoutMfa), stuck, NOT_FOUND).getStatus()).as("MFA 없는 운영자 세션").isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT refund_state FROM payment_refund_operation WHERE refund_operation_id = ?",
                String.class, stuck.operationId())).isEqualTo("UNKNOWN");
    }

    private static String completed(String providerRefundId) {
        return "{\"outcome\":\"COMPLETED\",\"providerRefundId\":\"" + providerRefundId + "\"}";
    }

    private static String path(Stuck stuck) {
        return "/api/internal/payment/refunds/" + stuck.operationId() + "/resolve-expired";
    }

    private MockHttpServletResponse resolve(Operator operator, Stuck stuck, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path(stuck))
                .header("Authorization", "Bearer " + operator.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String field(MockHttpServletResponse response, String name) throws Exception {
        return JsonMapper.builder().build().readTree(response.getContentAsString()).path(name).asString();
    }

    /** PayPal 응답이 끊겨 결과를 모르는 환불. PayPal stub 에는 환불이 생겼고 우리는 그 id 를 모른다. */
    private Stuck unknownRefund(Paid paid, String key) {
        disconnectNextRefundResponse();
        RefundResponse.Refund refund = refunds.refund(new OrderRequest.Refund(paid.sellerId(), paid.orderId(), key,
                List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1))));
        assertThat(refund.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.UNKNOWN);
        Map<String, Object> operation = jdbc.queryForMap("""
                SELECT operation.refund_operation_id, operation.provider_request_id
                FROM order_refund_request request
                JOIN payment_refund_operation operation
                  ON operation.refund_operation_id = request.payment_refund_operation_id
                WHERE request.refund_id = ?
                """, refund.refundId());
        return new Stuck(refund.refundId(), (UUID) operation.get("refund_operation_id"),
                operation.get("provider_request_id").toString(), refund.refundAmountWon());
    }

    /** 첫 요청 뒤 30일이 지나 다시 보내지 않고, 복구 worker가 창 끝에 운영자 검토로 넘긴 환불(ADR-0108). */
    private Stuck stuckRefund(Paid paid, String key) {
        Stuck stuck = unknownRefund(paid, key);
        jdbc.update("""
                UPDATE payment_refund_operation SET created_at = created_at - INTERVAL '31 days'
                WHERE refund_operation_id = ?
                """, stuck.operationId());
        jdbc.update("""
                UPDATE payment_recovery_job SET status = 'NEEDS_REVIEW', last_error_code = 'PROVIDER_RESULT_UNRESOLVED'
                WHERE payment_refund_operation_id = ?
                """, stuck.operationId());
        return stuck;
    }

    private String completedRefundId(Paid paid) {
        RefundResponse.Refund refund = refunds.refund(new OrderRequest.Refund(paid.sellerId(), paid.orderId(),
                "completed", List.of(new OrderRequest.Refund.Item(paid.orderItemId(), 1))));
        assertThat(refund.paymentStatus()).isEqualTo(OrderRefundPaymentStatus.COMPLETED);
        return refund.providerRefundId();
    }

    private Paid paidOrder(long unitPriceWon) {
        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId, new ProductRequest.CreateProduct("Expired refund product", null));
        String skuId = "expired-refund-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(), new ProductRequest.RegisterSellerSku(skuId, unitPriceWon, 1));
        String buyerRef = "expired-refund-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, 1);
        CheckoutResponse checkout = checkouts.checkout(buyerRef, "expired-refund-checkout-" + UUID.randomUUID());
        CheckoutResponse pending = checkoutPayments.submit(checkout.checkoutId());
        OrderViewResponse.Order order = checkoutPayments.completeApproval(checkout.checkoutId(),
                approvalToken(pending.paymentApprovalUrl())).orders().get(0);
        assertThat(order.state()).isEqualTo(OrderState.PAID);
        UUID orderItemId = jdbc.queryForObject("SELECT order_item_id FROM order_item WHERE order_id = ?",
                UUID.class, order.orderId());
        return new Paid(sellerId, order.orderId(), orderItemId);
    }

    /** 판매자에게 남은 몫(SELLER_PAYABLE + SELLER_RESERVE, 대변 − 차변). */
    private long sellerHeldWon(UUID sellerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END), 0)
                FROM settlement_entry
                WHERE seller_id = ? AND account_code IN ('SELLER_PAYABLE', 'SELLER_RESERVE')
                """, Long.class, sellerId);
    }

    private int refundJournals(UUID refundId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_journal WHERE source_refund_id = ?",
                Integer.class, refundId);
    }

    private String jobStatus(Stuck stuck) {
        return jdbc.queryForObject("SELECT status FROM payment_recovery_job WHERE payment_refund_operation_id = ?",
                String.class, stuck.operationId());
    }

    private UUID newAccount() {
        UUID accountId = accounts.createAccount("operator-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode(PASSWORD)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        return accountId;
    }

    private Operator session(UUID accountId) {
        AccountResponse.Login login = accountService.login(new AccountRequest.Login(email(accountId), PASSWORD), origin());
        return new Operator(accountId, login.tokens().accessToken());
    }

    private void grant(UUID accountId) {
        jdbc.update("INSERT INTO payout_operator_account(account_id, granted_by) VALUES (?, 'integration-test')", accountId);
    }

    /** TOTP를 등록하고 2차 인증(복구 코드)을 거친 로그인 세션을 가진 지급 운영자(ADR-0094). */
    private Operator operator() {
        UUID accountId = newAccount();
        grant(accountId);
        AccountResponse.MfaEnrollment enrollment = accountService.startMfaEnrollment(accountId);
        String code = new TOTPGenerator.Builder(enrollment.secret()).build().now();
        String recoveryCode = accountService.confirmMfaEnrollment(accountId, code).recoveryCodes().get(0);
        AccountResponse.Login login = accountService.login(new AccountRequest.Login(email(accountId), PASSWORD), origin());
        assertThat(login.mfaRequired()).isTrue();
        return new Operator(accountId, accountService.completeMfaLogin(
                new AccountRequest.MfaChallenge(login.challengeToken(), null, recoveryCode)).accessToken());
    }

    private String email(UUID accountId) {
        return jdbc.queryForObject("SELECT email FROM user_account WHERE account_id = ?", String.class, accountId);
    }

    private String origin() {
        return "198.51.100." + (origins.incrementAndGet() % 250 + 1);
    }

    private record Paid(UUID sellerId, UUID orderId, UUID orderItemId) {
    }

    private record Stuck(UUID refundId, UUID operationId, String providerRequestId, long amountWon) {
    }

    private record Operator(UUID accountId, String token) {
    }
}
