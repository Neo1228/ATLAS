package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.dto.response.LedgerAuditResponse;
import com.atlas.settlement.service.SettlementLedgerAuditService;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 원장 불변식 점검 질의가 실제 서비스 흐름이 만든 데이터에서 0건이고, 주입한 위반은 의도한 점검이 잡는지
 * 확인한다(EXP-111 H1, H1n).
 *
 * <p>흐름 데이터는 서비스 API(capture, checkout capture, 부분·전액 환불, 보류 소진 환불, 지급 확정과 환율
 * 반올림·수수료)로만 만든다. 음성 대조군은 트랜잭션 안에서 {@code session_replication_role = replica}로
 * 트리거와 외래 키를 건너뛰고 위반을 넣은 뒤 점검하고 롤백한다. 즉 소유 계정이 가드를 우회하는 경로를
 * 점검 질의가 잡는지 보는 시험이다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementLedgerAuditIntegrationTest extends PayPalIntegrationTestSupport {
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

    private static final Instant PERIOD_START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired private SettlementService settlement;
    @Autowired private SettlementPayoutService payouts;
    @Autowired private SettlementLedgerAuditService audits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private static UUID captureJournal;
    private static UUID checkoutJournal;
    private static UUID refundJournal;
    private static UUID payoutJournal;
    private static UUID checkoutSellerA;
    private static UUID checkoutSellerB;
    private static UUID refundId;
    private static UUID confirmedPayoutId;
    private static boolean seeded;

    /** 흐름 6종을 서비스로 만든다. 클래스 안에서 한 번만 만들고 모든 시험이 같은 데이터를 본다. */
    private void seedFlows() {
        if (seeded) {
            return;
        }
        // 1. capture 두 건(하나는 환불 없이 남고 하나는 환불·지급 대상)
        UUID sellerId = seller();
        UUID captureAttempt = UUID.randomUUID();
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), captureAttempt, sellerId, 10_000));
        captureJournal = journalByAttempt(captureAttempt);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 7_777));

        // 2. 보류 소진 환불(2,000 = 보류 1,000 + 지급 가능 1,000), 이어서 부분 환불 하나 더, 예약만 하고 해제한 환불 하나
        SettlementPostingRequest.Refund first = new SettlementPostingRequest.Refund(UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(first);
        settlement.recordRefund(first);
        refundId = first.refundId();
        refundJournal = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_refund_id = ?", UUID.class, refundId);
        SettlementPostingRequest.Refund second = new SettlementPostingRequest.Refund(UUID.randomUUID(), UUID.randomUUID(), sellerId, 333);
        settlement.reserveRefund(second);
        settlement.recordRefund(second);
        SettlementPostingRequest.Refund released = new SettlementPostingRequest.Refund(UUID.randomUUID(), UUID.randomUUID(), sellerId, 500);
        settlement.reserveRefund(released);
        settlement.releaseRefundReservation(released);

        // 3. 지급 확정(환율 반올림 11, 제공자 수수료 25)
        var payout = payouts.request(sellerId, PERIOD_START, PERIOD_END, "audit-payout-" + UUID.randomUUID());
        payouts.approve(payout.payoutId());
        payouts.beginDispatch(payout.payoutId());
        payouts.confirmProviderSuccess(payout.payoutId(), payout.amountWon() + 11, 11, 25);
        confirmedPayoutId = payout.payoutId();
        payoutJournal = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_payout_id = ?", UUID.class, confirmedPayoutId);

        // 4. 지급 요청만 하고 확정하지 않은 지급(분개 없음이 정상)
        UUID pendingSeller = seller();
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), pendingSeller, 5_000));
        payouts.request(pendingSeller, PERIOD_START, PERIOD_END, "audit-pending-" + UUID.randomUUID());

        // 5. checkout capture: 판매자 둘
        checkoutSellerA = seller();
        checkoutSellerB = seller();
        UUID checkoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won,
                    created_at, updated_at)
                VALUES (?, 'audit-buyer', ?, 'RESERVED', 25000, now(), now())
                """, checkoutId, "audit-" + checkoutId);
        UUID orderA = checkoutOrder(checkoutId, checkoutSellerA);
        UUID orderB = checkoutOrder(checkoutId, checkoutSellerB);
        settlement.recordCheckoutCapture(new SettlementPostingRequest.CheckoutSettlement(checkoutId,
                UUID.randomUUID(), 25_000, List.of(
                        new SettlementPostingRequest.SellerOrderAllocation(orderA, checkoutSellerA, 10_000),
                        new SettlementPostingRequest.SellerOrderAllocation(orderB, checkoutSellerB, 15_000))));
        checkoutJournal = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE checkout_id = ?", UUID.class, checkoutId);
        seeded = true;
    }

    @Test
    void flowDataSatisfiesEveryInvariant() {
        seedFlows();
        LedgerAuditResponse report = audits.audit();
        System.out.println("LEDGER-AUDIT clean " + describe(report) + " journals="
                + jdbc.queryForObject("SELECT count(*) FROM settlement_journal", Long.class)
                + " entries=" + jdbc.queryForObject("SELECT count(*) FROM settlement_entry", Long.class));
        assertThat(report.findings()).extracting(LedgerAuditResponse.Finding::check).containsExactly(
                "JOURNAL_UNBALANCED", "SELLER_UNBALANCED", "JOURNAL_WITHOUT_ENTRIES", "ENTRY_WITHOUT_JOURNAL",
                "CAPTURE_SPLIT_MISMATCH", "REFUND_CLEARING_MISMATCH", "REFUND_RESERVATION_MISMATCH",
                "CHECKOUT_ALLOCATION_MISMATCH", "PAYOUT_JOURNAL_MISMATCH");
        assertThat(report.totalViolations()).isZero();
        // 흐름이 네 종류의 분개와 보류 소진·부분 환불·해제 예약을 모두 만들었는지(빈 데이터로 통과하지 않는다)
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE source_attempt_id IS NOT NULL AND checkout_id IS NULL)
                     + count(*) FILTER (WHERE checkout_id IS NOT NULL)
                     + count(*) FILTER (WHERE source_refund_id IS NOT NULL)
                     + count(*) FILTER (WHERE source_payout_id IS NOT NULL)
                FROM settlement_journal
                """, Long.class)).isGreaterThanOrEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_refund_reservation WHERE status = 'RELEASED'", Long.class)).isEqualTo(1);
    }

    @Test
    void injectedViolationsAreCaughtByTheIntendedCheck() {
        seedFlows();
        Map<String, Consumer<JdbcTemplate>> injections = new LinkedHashMap<>();
        injections.put("JOURNAL_UNBALANCED", db -> db.update(
                "UPDATE settlement_entry SET amount_won = amount_won + 1 WHERE journal_id = ? AND account_code = 'SELLER_PAYABLE'",
                captureJournal));
        injections.put("SELLER_UNBALANCED", db -> {
            // 분개 전체는 균형으로 두고 판매자 A 항목은 +100, 판매자 B 항목은 -100
            db.update("UPDATE settlement_entry SET amount_won = amount_won + 100 WHERE journal_id = ? "
                    + "AND seller_id = ? AND account_code = 'SELLER_PAYABLE'", checkoutJournal, checkoutSellerA);
            db.update("UPDATE settlement_entry SET amount_won = amount_won - 100 WHERE journal_id = ? "
                    + "AND seller_id = ? AND account_code = 'SELLER_PAYABLE'", checkoutJournal, checkoutSellerB);
        });
        injections.put("JOURNAL_WITHOUT_ENTRIES", db -> db.update("""
                INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency,
                    gross_amount_won, recorded_at) VALUES (?, ?, ?, 'KRW', 100, now())
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        injections.put("ENTRY_WITHOUT_JOURNAL", db -> db.update("""
                INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
                VALUES (?, 'PSP_CLEARING', 'DEBIT', 1, now())
                """, UUID.randomUUID()));
        injections.put("CAPTURE_SPLIT_MISMATCH", db -> db.update(
                "UPDATE settlement_journal SET fee_amount_won = fee_amount_won + 1 WHERE journal_id = ?", captureJournal));
        injections.put("REFUND_CLEARING_MISMATCH", db -> db.update(
                "UPDATE settlement_journal SET gross_amount_won = gross_amount_won + 1 WHERE journal_id = ?", refundJournal));
        injections.put("REFUND_RESERVATION_MISMATCH", db -> db.update(
                "UPDATE settlement_refund_reservation SET status = 'RELEASED' WHERE refund_id = ?", refundId));
        injections.put("CHECKOUT_ALLOCATION_MISMATCH", db -> db.update(
                "UPDATE settlement_seller_allocation SET amount_won = amount_won + 1 WHERE journal_id = ? AND seller_id = ?",
                checkoutJournal, checkoutSellerA));
        injections.put("PAYOUT_JOURNAL_MISMATCH", db -> db.update(
                "UPDATE settlement_payout SET provider_fee_won = provider_fee_won + 1 WHERE payout_id = ?", confirmedPayoutId));

        assertThat(audits.audit().totalViolations()).as("baseline before injection").isZero();
        for (var injection : injections.entrySet()) {
            Map<String, Long> detected = transactions.execute(status -> {
                jdbc.execute("SET LOCAL session_replication_role = replica");
                injection.getValue().accept(jdbc);
                Map<String, Long> result = new LinkedHashMap<>();
                for (var finding : audits.audit().findings()) {
                    if (finding.violations() > 0) {
                        result.put(finding.check(), finding.violations());
                    }
                }
                status.setRollbackOnly();
                return result;
            });
            System.out.println("LEDGER-AUDIT injected " + injection.getKey() + " -> " + detected);
            assertThat(detected).as("injection " + injection.getKey()).containsKey(injection.getKey());
        }
        assertThat(audits.audit().totalViolations()).as("rolled back").isZero();
    }

    private String describe(LedgerAuditResponse report) {
        StringBuilder text = new StringBuilder();
        report.findings().forEach(finding -> text.append(finding.check()).append('=').append(finding.violations()).append(' '));
        return text.toString().trim();
    }

    private UUID journalByAttempt(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_attempt_id = ?", UUID.class, attemptId);
    }

    private UUID seller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }

    private UUID checkoutOrder(UUID checkoutId, UUID sellerId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref,
                    state, checkout_expires_at, created_at, updated_at, order_source, checkout_key,
                    checkout_id, coupon_discount_won)
                VALUES (?, ?, NULL, NULL, 'audit-buyer', 'PAID', now() + INTERVAL '10 minutes', now(), now(),
                    'CART', ?, ?, 0)
                """, orderId, sellerId, "key-" + orderId, checkoutId);
        return orderId;
    }
}
