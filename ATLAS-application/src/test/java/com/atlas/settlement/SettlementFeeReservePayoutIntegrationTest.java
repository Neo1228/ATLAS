package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.settlement.service.PayoutDispatchStore;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.service.PayoutDispatchPlan;
import com.atlas.settlement.service.PayoutDispatchRecord;
import com.atlas.settlement.dto.request.CapturePostingRequest;
import com.atlas.settlement.dto.request.CheckoutSettlementPostingRequest;
import com.atlas.settlement.dto.request.SellerOrderAllocationRequest;
import com.atlas.settlement.dto.request.RefundPostingRequest;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.dto.response.SellerSettlementStatementResponse;
import com.atlas.settlement.dto.request.SellerSettlementStatementRequest;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.dto.response.SettlementPayoutStatus;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import java.time.Instant;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 수수료·보류·지급이 실제 원장에서 정합적으로 동작하는지 검증한다.
 *
 * <p>mock 없이 실제 PostgreSQL 에 분개를 쓰고, 복식부기 항등식(차변 합 = 대변 합)과 계정 잔액을
 * 직접 계산해 확인한다. 지급 상태 기계도 실제 row 를 조건부 UPDATE 로 전이시킨다.
 *
 * <p>수수료율·보류율은 테스트 프로퍼티로 고정한다(각 10% = 1000 bps).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementFeeReservePayoutIntegrationTest extends PayPalIntegrationTestSupport {
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
        registry.add("atlas.settlement.fee-rate-bps", () -> "1000");
        registry.add("atlas.settlement.reserve-rate-bps", () -> "1000");
    }

    @Autowired private SettlementService settlement;
    @Autowired private SettlementPayoutService payouts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PayoutDispatchStore dispatches;

    @BeforeEach
    void clearLedgerAndPayouts() {
        // seller 는 테스트마다 새 UUID 지만, 지급 row 는 누적되면 pendingAmount 가 잔액을
        // 예약된 것으로 오판한다. 각 테스트는 빈 원장에서 시작해야 한다.
        jdbc.update("DELETE FROM settlement_payout_dispatch_outbox");
        jdbc.update("DELETE FROM settlement_payout_operator_action");
        jdbc.update("DELETE FROM settlement_refund_reservation");
        jdbc.update("DELETE FROM settlement_entry");
        jdbc.update("DELETE FROM settlement_seller_allocation");
        jdbc.update("DELETE FROM settlement_journal");
        jdbc.update("DELETE FROM settlement_payout");
    }

    @Test
    void payoutOutboxEnqueueIsIdempotentAndLeaseTokensFenceStaleWorkers() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "outbox-lease-payout");
        payouts.approve(payout.payoutId());
        PayoutDispatchPlan plan = new PayoutDispatchPlan(payout.payoutId(), 1, "v2:active:ciphertext",
                "US", 8_000, 584, new BigDecimal("1371.25000000"), "BOK_ECOS",
                LocalDate.parse("2026-11-21"), 8_009, 9,
                "sender-batch-lease", "sender-item-lease", "request-lease", "a".repeat(64));

        PayoutDispatchRecord first = dispatches.enqueue(plan);
        PayoutDispatchRecord duplicate = dispatches.enqueue(plan);
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(duplicate.payoutId()).isEqualTo(first.payoutId());

        PayoutDispatchRecord firstLease = dispatches.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(firstLease.leaseToken()).isNotNull();
        assertThat(dispatches.claim(1, Duration.ofSeconds(30))).isEmpty();
        assertThat(dispatches.reschedule(firstLease, Duration.ZERO, "TEST_RETRY", "PENDING")).isTrue();

        PayoutDispatchRecord secondLease = dispatches.claim(1, Duration.ofSeconds(30)).getFirst();
        assertThat(secondLease.leaseToken()).isNotEqualTo(firstLease.leaseToken());
        assertThat(dispatches.transition(firstLease, "FAILED", "STALE_WORKER")).isFalse();
        assertThat(dispatches.transition(secondLease, "CANCELLED", "TEST_CANCELLED")).isTrue();
        assertThat(dispatches.find(payout.payoutId()).orElseThrow().status()).isEqualTo("CANCELLED");
    }

    @Test
    void captureSplitsIntoFeeReserveAndSellerPayableWithBalancedEntries() {
        UUID sellerId = createSeller();
        UUID orderId = UUID.randomUUID();

        // gross 10,000 -> fee 1,000 / reserve 1,000 / seller 8,000
        settlement.recordCapture(new CapturePostingRequest(orderId, UUID.randomUUID(), sellerId, 10_000));

        Map<String, Long> debits = entryTotals(sellerId, "DEBIT");
        Map<String, Long> credits = entryTotals(sellerId, "CREDIT");

        assertThat(debits).containsEntry("PSP_CLEARING", 10_000L);
        assertThat(credits).containsEntry("SELLER_PAYABLE", 8_000L);
        assertThat(credits).containsEntry("PLATFORM_FEE", 1_000L);
        assertThat(credits).containsEntry("SELLER_RESERVE", 1_000L);

        // 복식부기 항등식: 차변 합 = 대변 합
        assertThat(sum(debits)).isEqualTo(sum(credits)).isEqualTo(10_000L);

        // journal 에 적용 요율이 스냅샷된다.
        Map<String, Object> journal = jdbc.queryForMap("""
                SELECT fee_amount_won, reserve_amount_won, fee_rate_bps, reserve_rate_bps
                FROM settlement_journal WHERE source_attempt_id IS NOT NULL AND seller_id = ?
                """, sellerId);
        assertThat(((Number) journal.get("fee_amount_won")).longValue()).isEqualTo(1_000L);
        assertThat(((Number) journal.get("reserve_amount_won")).longValue()).isEqualTo(1_000L);
        assertThat(((Number) journal.get("fee_rate_bps")).intValue()).isEqualTo(1_000);
        assertThat(((Number) journal.get("reserve_rate_bps")).intValue()).isEqualTo(1_000);
    }

    @Test
    void checkoutCaptureSplitsFeesPerSellerAndKeepsDoubleEntryBalanced() {
        UUID sellerA = createSeller();
        UUID sellerB = createSeller();
        // checkout journal 은 buyer_checkout FK 를 갖는다. 실제 row 를 만들어야 한다.
        UUID checkoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'RESERVED', 25000, now(), now())
                """, checkoutId, "settlement-fee-buyer", "settlement-fee-" + checkoutId);
        // settlement_seller_allocation 은 purchase_order FK 를 갖는다.
        UUID orderA = createCheckoutOrder(checkoutId, sellerA);
        UUID orderB = createCheckoutOrder(checkoutId, sellerB);

        settlement.recordCheckoutCapture(new CheckoutSettlementPostingRequest(checkoutId,
                UUID.randomUUID(), 25_000, List.of(
                        new SellerOrderAllocationRequest(orderA, sellerA, 10_000),
                        new SellerOrderAllocationRequest(orderB, sellerB, 15_000))));

        // seller 마다 자기 몫만 본다. 각 10% 수수료·보류.
        assertThat(payouts.balance(sellerA).payableAmountWon().longValueExact()).isEqualTo(8_000L);
        assertThat(payouts.balance(sellerA).reserveAmountWon().longValueExact()).isEqualTo(1_000L);
        assertThat(payouts.balance(sellerB).payableAmountWon().longValueExact()).isEqualTo(12_000L);
        assertThat(payouts.balance(sellerB).reserveAmountWon().longValueExact()).isEqualTo(1_500L);

        // 복식부기: journal 전체에서 차변 합 = 대변 합 = checkout 총액
        Map<String, Long> debits = journalEntryTotals(checkoutId, "DEBIT");
        Map<String, Long> credits = journalEntryTotals(checkoutId, "CREDIT");
        assertThat(sum(debits)).isEqualTo(25_000L);
        assertThat(sum(credits)).isEqualTo(25_000L);
    }

    @Test
    void balanceReflectsCaptureRefundAndReserve() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        RefundPostingRequest refund = new RefundPostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(refund);
        settlement.recordRefund(refund);

        var balance = payouts.balance(sellerId);
        // capture: payable 8,000 / reserve 1,000 / fee 1,000.
        // refund 2,000 은 보류에서 먼저 차감(1,000)되고 나머지 1,000 만 payable 에서 나간다.
        assertThat(balance.payableAmountWon().longValueExact()).isEqualTo(7_000L);
        assertThat(balance.reserveAmountWon().longValueExact()).isZero();
        assertThat(balance.feeAmountWon().longValueExact()).isEqualTo(1_000L);
        // 복식부기: 두 계정 변화의 합이 capture - refund 와 맞는다.
        assertThat(balance.payableAmountWon().add(balance.reserveAmountWon()).longValueExact())
                .isEqualTo(8_000L - 1_000L);
    }

    @Test
    void payoutRequestUsesLedgerBalanceAndRejectsDuplicatePeriod() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:00Z");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ?", Integer.class, sellerId))
                .as("payouts for this seller before request").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_won),0) FROM settlement_payout WHERE seller_id = ? "
                + "AND state IN ('REQUESTED','APPROVED','SENT')", Long.class, sellerId))
                .as("pending amount before request").isZero();
        SellerPayoutResponse created = payouts.request(sellerId, start, end, "payout-key-1");
        // 지급 금액은 호출자가 아니라 원장 잔액이 정한다(보류 1,000 제외).
        assertThat(created.amountWon()).isEqualTo(8_000L);
        assertThat(created.state()).isEqualTo(SettlementPayoutStatus.REQUESTED);

        // 같은 기간 재요청은 중복 지급을 만들지 않고 기존 지급을 돌려준다.
        SellerPayoutResponse duplicate = payouts.request(sellerId, start, end, "payout-key-2");
        assertThat(duplicate.payoutId()).isEqualTo(created.payoutId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ?", Integer.class, sellerId))
                .isEqualTo(1);
    }

    @Test
    void definitiveRefundFailureReleasesItsHoldForANewPayoutRequest() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        RefundPostingRequest refund = new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(refund);
        SellerPayoutResponse first = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "refund-held-payout");
        assertThat(first.amountWon()).isEqualTo(6_000);

        settlement.releaseRefundReservation(refund);

        SellerPayoutResponse second = payouts.request(sellerId,
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                "refund-release-payout");
        assertThat(second.amountWon()).isEqualTo(2_000);
        assertThat(payouts.summary(sellerId).pendingRefundAmountWon()).isZero();
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(8_000);
    }

    @Test
    void refundCommitmentPreemptsApprovedPayoutBeforeProviderDispatch() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:00Z");
        SellerPayoutResponse payout = payouts.request(sellerId, start, end, "refund-preempted-payout");
        payouts.approve(payout.payoutId());
        RefundPostingRequest refund = new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);

        settlement.reserveRefund(refund);

        SellerPayoutResponse cancelled = payouts.find(payout.payoutId());
        assertThat(cancelled.state()).isEqualTo(SettlementPayoutStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(payouts.beginDispatch(payout.payoutId())).isFalse();
        SellerPayoutResponse replacement = payouts.request(sellerId, start, end, "replacement-payout");
        assertThat(replacement.amountWon()).isEqualTo(6_000);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(6_000);
        assertThat(payouts.summary(sellerId).pendingRefundAmountWon()).isEqualTo(2_000);
        assertThat(payouts.summary(sellerId).availablePayoutAmountWon()).isZero();
    }

    @Test
    void refundPostingPreservesPayoutCreatedFromBalanceAfterTheRefundHold() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 12_500));
        RefundPostingRequest refund = new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(refund);
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:00Z");

        SellerPayoutResponse payout = payouts.request(sellerId, start, end, "refund-covered-payout");
        assertThat(payout.amountWon()).isEqualTo(8_000);
        settlement.reserveRefund(refund);
        settlement.recordRefund(refund);

        assertThat(payouts.find(payout.payoutId()).state()).isEqualTo(SettlementPayoutStatus.REQUESTED);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(8_000);
        assertThat(payouts.summary(sellerId).pendingRefundAmountWon()).isZero();
        assertThat(payouts.balance(sellerId).payableAmountWon()).isEqualTo(9_250);
    }

    @Test
    void refundAfterDispatchFenceKeepsPayoutReservedForProviderReconciliation() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "dispatch-wins-payout");
        payouts.approve(payout.payoutId());
        assertThat(payouts.beginDispatch(payout.payoutId())).isTrue();

        settlement.reserveRefund(new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000));

        assertThat(payouts.find(payout.payoutId()).state()).isEqualTo(SettlementPayoutStatus.DISPATCHING);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(8_000);
        assertThat(payouts.summary(sellerId).pendingRefundAmountWon()).isEqualTo(2_000);
        assertThat(payouts.summary(sellerId).availablePayoutAmountWon()).isZero();
    }

    @Test
    void concurrentRefundReservationAndDispatchHaveOneSellerLockWinner() throws Exception {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "concurrent-refund-dispatch");
        payouts.approve(payout.payoutId());
        RefundPostingRequest refund = new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> dispatch = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Dispatch start timed out");
                return payouts.beginDispatch(payout.payoutId());
            });
            Future<Void> reserveRefund = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Refund start timed out");
                settlement.reserveRefund(refund);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean dispatchStarted = dispatch.get(10, TimeUnit.SECONDS);
            reserveRefund.get(10, TimeUnit.SECONDS);

            SettlementPayoutStatus finalState = payouts.find(payout.payoutId()).state();
            assertThat(dispatchStarted).isEqualTo(finalState == SettlementPayoutStatus.DISPATCHING);
            assertThat(finalState).isIn(SettlementPayoutStatus.CANCELLED, SettlementPayoutStatus.DISPATCHING);
            assertThat(payouts.summary(sellerId).pendingRefundAmountWon()).isEqualTo(2_000);
            assertThat(payouts.summary(sellerId).pendingPayoutAmountWon())
                    .isEqualTo(dispatchStarted ? 8_000 : 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulRefundAfterDispatchedPayoutSurfacesNegativePayableCreditExposure() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "refund-credit-exposure-payout");
        payouts.approve(payout.payoutId());
        assertThat(payouts.beginDispatch(payout.payoutId())).isTrue();
        RefundPostingRequest refund = new RefundPostingRequest(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(refund);

        payouts.confirmProviderSuccess(payout.payoutId(), 8_000, 0, 0);
        settlement.recordRefund(refund);

        assertThat(payouts.balance(sellerId).payableAmountWon()).isEqualTo(-1_000);
        assertThat(payouts.riskSnapshot().negativePayableSellerCount()).isEqualTo(1);
        assertThat(payouts.riskSnapshot().negativePayableAmountWon()).isEqualTo(1_000);
        assertThat(payouts.riskSnapshot().pendingRefundReservationCount()).isZero();
        assertThat(payouts.riskSnapshot().pendingRefundReservationAmountWon()).isZero();
    }

    @Test
    void providerSuccessPostsSellerPrincipalRoundingAndFeeAsBalancedLedgerEntries() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "provider-confirmed-payout");
        payouts.approve(payout.payoutId());
        payouts.beginDispatch(payout.payoutId());

        SellerPayoutResponse confirmed = payouts.confirmProviderSuccess(payout.payoutId(), 8_011, 11, 25);
        assertThat(payouts.confirmProviderSuccess(payout.payoutId(), 8_011, 11, 25).state())
                .isEqualTo(SettlementPayoutStatus.CONFIRMED);
        assertThatThrownBy(() -> payouts.confirmProviderSuccess(payout.payoutId(), 8_011, 11, 26))
                .isInstanceOf(SettlementException.class);

        assertThat(confirmed.state()).isEqualTo(SettlementPayoutStatus.CONFIRMED);
        Map<String, Long> debits = entryTotals(sellerId, "DEBIT");
        Map<String, Long> credits = entryTotals(sellerId, "CREDIT");
        assertThat(debits).containsEntry("SELLER_PAYABLE", 8_000L)
                .containsEntry("PLATFORM_PAYOUT_FX_ROUNDING", 11L)
                .containsEntry("PLATFORM_PAYOUT_FEE", 25L);
        assertThat(credits).containsEntry("PSP_CLEARING", 8_036L);
        assertThat(sum(debits)).isEqualTo(sum(credits));
        assertThat(payouts.balance(sellerId).payableAmountWon()).isZero();
    }

    @Test
    void providerFailureEvidenceCanReleaseAnAttemptedPayoutReservation() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse payout = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"),
                "provider-failed-payout");
        payouts.approve(payout.payoutId());
        payouts.beginDispatch(payout.payoutId());
        payouts.markProviderUnknown(payout.payoutId());

        SellerPayoutResponse failed = payouts.failWithProviderEvidence(
                payout.payoutId(), "PAYOUT_ITEM_FAILED");

        assertThat(failed.state()).isEqualTo(SettlementPayoutStatus.FAILED);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isZero();
        assertThat(payouts.request(sellerId, payout.periodStart(), payout.periodEnd(),
                "provider-failure-replacement").amountWon()).isEqualTo(8_000);
    }

    @Test
    void confirmedPayoutPostsBalancedJournalAndZeroesPayable() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:00Z");
        SellerPayoutResponse requested = payouts.request(sellerId, start, end, "payout-confirm-key");

        payouts.approve(requested.payoutId());
        payouts.markSent(requested.payoutId());
        SellerPayoutResponse confirmed = payouts.confirm(requested.payoutId());

        assertThat(confirmed.state()).isEqualTo(SettlementPayoutStatus.CONFIRMED);
        // 지급 후 지급 가능 잔액은 0 이 된다(원장 분개로 반영).
        assertThat(payouts.balance(sellerId).payableAmountWon()).isZero();
        // payout journal 도 복식부기 항등식을 만족한다.
        Map<String, Long> debits = entryTotals(sellerId, "DEBIT");
        Map<String, Long> credits = entryTotals(sellerId, "CREDIT");
        assertThat(sum(debits)).isEqualTo(sum(credits));

        // 확정은 멱등이다.
        assertThat(payouts.confirm(requested.payoutId()).state())
                .isEqualTo(SettlementPayoutStatus.CONFIRMED);
    }

    @Test
    void pendingPayoutReservesBalanceSoDifferentPeriodsCannotDoublePay() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        // payable 8,000 (보류 1,000, 수수료 1,000)

        SellerPayoutResponse first = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "first-period");
        assertThat(first.amountWon()).isEqualTo(8_000L);

        // 확정 전이라도 그 금액은 이미 예약됐다. 다른 기간으로 또 요청하면 잔액이 없다.
        assertThatThrownBy(() -> payouts.request(sellerId,
                Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), "second-period"))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.CONFLICT));

        // 남은 지급 가능액은 0 이고, 예약된 금액은 pending 으로 보인다.
        var summary = payouts.summary(sellerId);
        assertThat(summary.pendingPayoutAmountWon()).isEqualTo(8_000L);
        assertThat(summary.payableAmountWon() - summary.pendingPayoutAmountWon()).isZero();
    }

    @Test
    void concurrentDifferentPeriodPayoutRequestsCannotDoubleReserve() throws Exception {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));

        int racers = 4;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(racers)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < racers; index++) {
                int slot = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return payouts.request(sellerId,
                                Instant.parse("2026-11-01T00:00:00Z").plusSeconds(86_400L * slot),
                                Instant.parse("2026-12-01T00:00:00Z").plusSeconds(86_400L * slot),
                                "race-period-" + slot);
                    } catch (RuntimeException rejected) {
                        return rejected;
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        // 동시 요청이 모두 같은 잔액을 예약하면 안 된다. 예약된 총액은 payable 을 넘지 않는다.
        long reserved = payouts.summary(sellerId).pendingPayoutAmountWon();
        assertThat(reserved).isLessThanOrEqualTo(8_000L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ?
                """, Integer.class, sellerId)).isEqualTo(1);
    }

    @Test
    void payoutCannotBeConfirmedBeforeItIsSent() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse requested = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "skip-sent-key");

        assertThatThrownBy(() -> payouts.confirm(requested.payoutId()))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.CONFLICT));
    }

    @Test
    void sentPayoutCannotBeFailedWithoutProviderEvidence() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant start = Instant.parse("2026-11-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:00Z");
        SellerPayoutResponse requested = payouts.request(sellerId, start, end, "sent-unknown-key");
        payouts.approve(requested.payoutId());
        payouts.markSent(requested.payoutId());

        assertThatThrownBy(() -> payouts.fail(requested.payoutId(), "PROVIDER_RESPONSE_UNKNOWN"))
                .isInstanceOfSatisfying(SettlementException.class,
                        error -> assertThat(error.code()).isEqualTo(SettlementException.Code.CONFLICT));
        assertThat(payouts.find(requested.payoutId()).state()).isEqualTo(SettlementPayoutStatus.SENT);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(8_000L);

        SellerPayoutResponse replay = payouts.request(sellerId, start, end, "sent-unknown-replay");
        assertThat(replay.payoutId()).isEqualTo(requested.payoutId());
        assertThat(replay.amountWon()).isEqualTo(8_000L);
    }

    @Test
    void concurrentConfirmOnlySucceedsOnceAndPostsOneJournal() throws Exception {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SellerPayoutResponse requested = payouts.request(sellerId,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"), "race-key");
        payouts.approve(requested.payoutId());
        payouts.markSent(requested.payoutId());

        int racers = 4;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(racers)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < racers; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return payouts.confirm(requested.payoutId());
                    } catch (RuntimeException rejected) {
                        return rejected;
                    }
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_journal WHERE source_payout_id = ?
                """, Integer.class, requested.payoutId())).isEqualTo(1);
        assertThat(payouts.balance(sellerId).payableAmountWon()).isZero();
    }

    @Test
    void statementExposesBalanceBreakdownAlongsidePeriodFlow() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new CapturePostingRequest(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

        SellerSettlementStatementResponse statement = settlement.getSellerStatement(
                new SellerSettlementStatementRequest(sellerId, from, to, 20, null));

        SellerSettlementStatementResponse.Summary summary = statement.totals();
        assertThat(summary.capturedAmountWon().longValueExact()).isEqualTo(10_000L);
        assertThat(summary.feeAmountWon().longValueExact()).isEqualTo(1_000L);
        assertThat(summary.reserveAmountWon().longValueExact()).isEqualTo(1_000L);
        assertThat(summary.payableAmountWon().longValueExact()).isEqualTo(8_000L);
        assertThat(summary.pendingPayoutAmountWon().longValueExact()).isZero();
        assertThat(summary.confirmedPayoutAmountWon().longValueExact()).isZero();
    }

    private Map<String, Long> journalEntryTotals(UUID checkoutId, String side) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query("""
                SELECT entry.account_code, SUM(entry.amount_won) AS total
                FROM settlement_entry entry
                JOIN settlement_journal journal ON journal.journal_id = entry.journal_id
                WHERE journal.checkout_id = ? AND entry.entry_side = ?
                GROUP BY entry.account_code
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("account_code"), resultSet.getLong("total")),
                checkoutId, side)
                .forEach(entry -> totals.merge(entry.getKey(), entry.getValue(), Long::sum));
        return totals;
    }

    private UUID createCheckoutOrder(UUID checkoutId, UUID sellerId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref,
                    state, checkout_expires_at, created_at, updated_at, order_source, checkout_key,
                    checkout_id, coupon_discount_won)
                VALUES (?, ?, NULL, NULL, ?, 'PAID', now() + INTERVAL '10 minutes', now(), now(),
                    'CART', ?, ?, 0)
                """, orderId, sellerId, "settlement-fee-buyer", "key-" + orderId, checkoutId);
        return orderId;
    }

    private UUID createSeller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }

    private Map<String, Long> entryTotals(UUID sellerId, String side) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query("""
                SELECT account_code, SUM(amount_won) AS total
                FROM settlement_entry WHERE seller_id = ? AND entry_side = ?
                GROUP BY account_code
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("account_code"), resultSet.getLong("total")), sellerId, side)
                .forEach(entry -> totals.merge(entry.getKey(), entry.getValue(), Long::sum));
        return totals;
    }

    private static long sum(Map<String, Long> totals) {
        return totals.values().stream().mapToLong(Long::longValue).sum();
    }
}
