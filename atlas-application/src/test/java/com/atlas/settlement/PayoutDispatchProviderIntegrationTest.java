package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.service.payout.PayoutDispatchApprovalService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.model.PayoutDispatchRecord;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import com.atlas.support.AppendOnlyTestReset;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 정산 원장에서 만든 지급을 워커가 PayPal Payouts로 보내고 결과를 원장에 반영하는 전체 경로를 PostgreSQL과 실제 워커로 확인한다.
 *
 * <p>PayPal은 실제 Sandbox에서 관측한 응답을 따라 하는 가짜 서버다(EXP-073): 생성은 {@code items} 없이 PENDING,
 * 조회는 PENDING, PROCESSING, 항목 SUCCESS, 배치 SUCCESS 순으로 진행하고, 같은 {@code sender_batch_id}는 새 request ID든
 * 같은 request ID든 400으로 거부하며 원래 배치는 {@code details[].link}에 담는다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class PayoutDispatchProviderIntegrationTest extends PayPalIntegrationTestSupport {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    private static final FakePayouts FAKE = new FakePayouts();
    private static Dispatcher originalDispatcher;

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
        registry.add("atlas.payment.paypal.payouts.worker.enabled", () -> "true");
        registry.add("atlas.payment.paypal.payouts.worker.initial-delay", () -> "PT0S");
        registry.add("atlas.payment.paypal.payouts.worker.poll-interval", () -> "PT0.2S");
    }

    @Autowired private SettlementService settlement;
    @Autowired private SettlementPayoutService payouts;
    @Autowired private PayoutDispatchApprovalService approvals;
    @Autowired private PayoutDispatchStore dispatches;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void installFakePayouts() {
        synchronized (PayoutDispatchProviderIntegrationTest.class) {
            if (originalDispatcher == null) {
                originalDispatcher = PAYPAL.getDispatcher();
                PAYPAL.setDispatcher(new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                        MockResponse payout = FAKE.handle(request);
                        return payout != null ? payout : originalDispatcher.dispatch(request);
                    }
                });
            }
        }
        FAKE.reset();
        jdbc.update("DELETE FROM settlement_payout_dispatch_outbox");
        AppendOnlyTestReset.clear(jdbc, "settlement_payout_operator_action");
    }

    @AfterAll
    static void restoreDispatcher() {
        synchronized (PayoutDispatchProviderIntegrationTest.class) {
            if (originalDispatcher != null) {
                PAYPAL.setDispatcher(originalDispatcher);
                originalDispatcher = null;
            }
        }
    }

    @Test
    void approvedPayoutIsSentOnceAndConfirmedWithTheProviderFee() {
        Seeded seeded = seedApprovedPayout("seller-" + UUID.randomUUID() + "@example.test");

        awaitState(seeded.payoutId(), SettlementPayoutState.CONFIRMED);

        PayoutDispatchRecord record = dispatches.find(seeded.payoutId()).orElseThrow();
        assertThat(record.status()).isEqualTo(PayoutDispatchState.COMPLETED);
        assertThat(record.providerBatchId()).isEqualTo(FAKE.onlyBatchId());
        assertThat(record.providerItemStatus()).isEqualTo("SUCCESS");
        assertThat(record.providerFeeMinor()).isEqualTo(2L);
        assertThat(FAKE.acceptedBatches()).as("PayPal에 만들어진 배치").isEqualTo(1);
        assertThat(payouts.balance(seeded.sellerId()).payableAmountWon().signum()).isZero();
    }

    @Test
    void lostCreateResponseRecoversThroughTheDuplicateRejectionWithoutASecondPayout() {
        FAKE.dropNextCreateResponse();
        Seeded seeded = seedApprovedPayout("seller-" + UUID.randomUUID() + "@example.test");

        awaitState(seeded.payoutId(), SettlementPayoutState.CONFIRMED);

        PayoutDispatchRecord record = dispatches.find(seeded.payoutId()).orElseThrow();
        assertThat(FAKE.createRequests()).as("첫 응답을 잃은 뒤 같은 sender_batch_id로 다시 보냄").isEqualTo(2);
        assertThat(FAKE.acceptedBatches()).as("PayPal에는 배치가 한 번만 만들어짐").isEqualTo(1);
        assertThat(FAKE.duplicateRejections()).isEqualTo(1);
        assertThat(record.providerBatchId()).as("중복 거부 본문에서 원래 배치를 찾아 이어 감").isEqualTo(FAKE.onlyBatchId());
        assertThat(record.status()).isEqualTo(PayoutDispatchState.COMPLETED);
        assertThat(payouts.balance(seeded.sellerId()).payableAmountWon().signum()).isZero();
    }

    @Test
    void batchSuccessWithAnUnclaimedItemNeverConfirmsThePayout() {
        Seeded seeded = seedApprovedPayout("unclaimed-" + UUID.randomUUID() + "@example.test");

        awaitState(seeded.payoutId(), SettlementPayoutState.UNCLAIMED);
        awaitTrue("배치가 SUCCESS가 될 때까지 조회", () -> FAKE.batchReachedSuccess());

        PayoutResponse.SellerPayout payout = payouts.find(seeded.payoutId());
        assertThat(payout.state()).isEqualTo(SettlementPayoutState.UNCLAIMED);
        assertThat(payout.confirmedAt()).isNull();
        assertThat(dispatches.find(seeded.payoutId()).orElseThrow().status())
                .isNotEqualTo(PayoutDispatchState.COMPLETED);
        assertThat(payouts.balance(seeded.sellerId()).payableAmountWon().signum())
                .as("미수령 지급은 지급 가능 금액을 줄이지 않거나 예약 상태로 남는다").isNotNegative();
    }

    private Seeded seedApprovedPayout(String recipientEmail) {
        UUID sellerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        jdbc.update("INSERT INTO user_account(account_id, email, password_hash, email_verified_at) VALUES (?, ?, ?, now())",
                accountId, recipientEmail, "hash");
        jdbc.update("INSERT INTO seller_account(account_id, seller_id) VALUES (?, ?)", accountId, sellerId);
        jdbc.update("""
                INSERT INTO seller_payout_profile (seller_id, recipient_account_id, country_code, payout_currency,
                    status, profile_version, created_at, updated_at, verified_at, verified_by)
                VALUES (?, ?, 'US', 'USD', 'ACTIVE', 1, now(), now(), now(), 'test-operator')
                """, sellerId, accountId);
        settlement.recordCapture(new SettlementPostingRequest.Capture(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant now = Instant.now();
        PayoutResponse.SellerPayout payout = payouts.request(sellerId,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), "e2e-" + UUID.randomUUID());
        approvals.approve(payout.payoutId(), "approve-" + UUID.randomUUID(), "test-operator", "integration test approval");
        return new Seeded(sellerId, payout.payoutId());
    }

    private void awaitState(UUID payoutId, SettlementPayoutState expected) {
        awaitTrue("지급 상태 " + expected, () -> {
            SettlementPayoutState actual = payouts.find(payoutId).state();
            if (actual == expected) return true;
            if (actual == SettlementPayoutState.FAILED || actual == SettlementPayoutState.NEEDS_REVIEW
                    || actual == SettlementPayoutState.CANCELLED) {
                PayoutDispatchRecord record = dispatches.find(payoutId).orElse(null);
                throw new AssertionError("기대 " + expected + ", 실제 " + actual + ", 디스패치 "
                        + (record == null ? "없음" : record.status() + "/" + record.lastErrorCode())
                        + ", 지급 가능 금액 " + payouts.balance(payouts.find(payoutId).sellerId()).payableAmountWon());
            }
            return false;
        });
    }

    private static void awaitTrue(String what, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("시간 안에 이루어지지 않음: " + what);
    }

    private record Seeded(UUID sellerId, UUID payoutId) {}

    /** 실제 Sandbox에서 관측한 Payouts 응답 모양과 진행 순서를 따라 하는 가짜. */
    private static final class FakePayouts {
        private final Map<String, Batch> byId = new ConcurrentHashMap<>();
        private final Map<String, Batch> bySender = new ConcurrentHashMap<>();
        private final AtomicInteger createRequests = new AtomicInteger();
        private final AtomicInteger accepted = new AtomicInteger();
        private final AtomicInteger duplicates = new AtomicInteger();
        private final AtomicBoolean dropNext = new AtomicBoolean();
        private final AtomicBoolean successSeen = new AtomicBoolean();

        void reset() {
            byId.clear();
            bySender.clear();
            createRequests.set(0);
            accepted.set(0);
            duplicates.set(0);
            dropNext.set(false);
            successSeen.set(false);
        }

        void dropNextCreateResponse() {
            dropNext.set(true);
        }

        int createRequests() {
            return createRequests.get();
        }

        int acceptedBatches() {
            return accepted.get();
        }

        int duplicateRejections() {
            return duplicates.get();
        }

        boolean batchReachedSuccess() {
            return successSeen.get();
        }

        String onlyBatchId() {
            assertThat(byId).hasSize(1);
            return byId.keySet().iterator().next();
        }

        MockResponse handle(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath().split("\\?", 2)[0];
            String method = request.getMethod();
            if (path.equals("/v1/payments/payouts") && "POST".equals(method)) {
                return create(request.getBody().clone().readUtf8());
            }
            if (path.matches("/v1/payments/payouts/[^/]+") && "GET".equals(method)) {
                return get(path.substring(path.lastIndexOf('/') + 1));
            }
            return null;
        }

        private MockResponse create(String body) {
            createRequests.incrementAndGet();
            String senderBatchId = text(body, "sender_batch_id");
            Batch existing = bySender.get(senderBatchId);
            if (existing != null) {
                duplicates.incrementAndGet();
                return new MockResponse().setResponseCode(400).addHeader("Content-Type", "application/json")
                        .setBody("{\"name\":\"USER_BUSINESS_ERROR\",\"message\":\"User business error.\",\"debug_id\":\"debug\","
                                + "\"information_link\":\"https://developer.paypal.com/docs/api/payments.payouts-batch/#errors\","
                                + "\"details\":[{\"field\":\"SENDER_BATCH_ID\",\"location\":\"body\","
                                + "\"issue\":\"Batch with given sender_batch_id already exists\","
                                + "\"link\":[{\"href\":\"https://api.sandbox.paypal.com/v1/payments/payouts/" + existing.id
                                + "\",\"rel\":\"self\",\"method\":\"GET\",\"encType\":\"application/json\"}]}],\"links\":[]}");
            }
            Batch batch = new Batch("PAYOUT" + (byId.size() + 1) + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    senderBatchId, text(body, "sender_item_id"), text(body, "receiver"), text(body, "value"));
            byId.put(batch.id, batch);
            bySender.put(senderBatchId, batch);
            accepted.incrementAndGet();
            if (dropNext.compareAndSet(true, false)) {
                // PayPal은 배치를 만들었지만 응답이 호출자에게 닿지 않는다.
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            }
            return json(201, "{\"batch_header\":{\"payout_batch_id\":\"" + batch.id + "\",\"batch_status\":\"PENDING\","
                    + "\"sender_batch_header\":{\"sender_batch_id\":\"" + batch.senderBatchId
                    + "\",\"email_subject\":\"You have a payout\"}},\"links\":[{\"href\":\"https://api.sandbox.paypal.com/v1/payments/payouts/"
                    + batch.id + "\",\"rel\":\"self\",\"method\":\"GET\",\"encType\":\"application/json\"}]}");
        }

        private MockResponse get(String id) {
            Batch batch = byId.get(id);
            if (batch == null) {
                return new MockResponse().setResponseCode(404).setBody("{\"name\":\"RESOURCE_NOT_FOUND\"}");
            }
            int call = batch.gets.incrementAndGet();
            boolean unclaimed = batch.receiver.startsWith("unclaimed-");
            String batchStatus = call == 1 ? "PENDING" : call == 2 ? "PROCESSING" : "SUCCESS";
            String itemStatus = call == 1 ? "PENDING" : unclaimed ? "UNCLAIMED" : "SUCCESS";
            if (call >= 3) successSeen.set(true);
            String fee = unclaimed || call == 1 ? "" : ",\"payout_item_fee\":{\"currency\":\"USD\",\"value\":\"0.02\"}";
            return json(200, "{\"batch_header\":{\"payout_batch_id\":\"" + batch.id + "\",\"batch_status\":\"" + batchStatus + "\","
                    + "\"sender_batch_header\":{\"sender_batch_id\":\"" + batch.senderBatchId + "\",\"email_subject\":\"You have a payout\"},"
                    + "\"amount\":{\"currency\":\"USD\",\"value\":\"" + batch.amount + "\"},"
                    + "\"fees\":{\"currency\":\"USD\",\"value\":\"" + (unclaimed ? "0.00" : "0.02") + "\"}},"
                    + "\"items\":[{\"payout_item_id\":\"ITEM" + batch.id + "\",\"transaction_status\":\"" + itemStatus + "\","
                    + "\"payout_batch_id\":\"" + batch.id + "\"" + fee + ",\"payout_item\":{\"recipient_type\":\"EMAIL\","
                    + "\"amount\":{\"currency\":\"USD\",\"value\":\"" + batch.amount + "\"},"
                    + "\"receiver\":\"" + batch.receiver + "\",\"sender_item_id\":\"" + batch.senderItemId + "\"}}]}");
        }

        private static MockResponse json(int status, String body) {
            return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json").setBody(body);
        }

        private static String text(String json, String key) {
            Matcher matcher = Pattern
                    .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static final class Batch {
            final String id;
            final String senderBatchId;
            final String senderItemId;
            final String receiver;
            final String amount;
            final AtomicInteger gets = new AtomicInteger();

            Batch(String id, String senderBatchId, String senderItemId, String receiver, String amount) {
                this.id = id;
                this.senderBatchId = senderBatchId;
                this.senderItemId = senderItemId;
                this.receiver = receiver;
                this.amount = amount;
            }
        }
    }
}
