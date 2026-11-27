package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.service.SettlementLedgerAuditService;
import com.atlas.settlement.service.SettlementService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
 * V7 가드: 원장 추가 전용 트리거와 분개 균형 지연 제약 트리거가 위반을 거절하고 정상 흐름은 막지 않는지,
 * 소유자의 정정 경로(break-glass)와 롤백 절차가 문서대로 동작하는지 실제 PostgreSQL로 확인한다(EXP-111).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementLedgerGuardIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String APPEND_ONLY = "settlement_ledger_is_append-only";
    private static final String UNBALANCED = "settlement_journal_unbalanced";
    private static final Path ROLLBACK_SCRIPT =
            Path.of("..", "scripts", "db", "rollback", "V7__undo_settlement_ledger_guards.sql").toAbsolutePath().normalize();

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
    @Autowired private SettlementLedgerAuditService audits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    void ledgerRowsCannotBeUpdatedDeletedOrTruncated() {
        UUID sellerId = capture(10_000);
        long journals = count("settlement_journal");
        long entries = count("settlement_entry");

        for (String statement : List.of(
                "UPDATE settlement_entry SET amount_won = amount_won",
                "DELETE FROM settlement_entry",
                "UPDATE settlement_journal SET fee_amount_won = fee_amount_won",
                "DELETE FROM settlement_journal",
                "TRUNCATE settlement_entry",
                "TRUNCATE settlement_seller_allocation",
                // CASCADE 는 자식 테이블의 TRUNCATE 트리거도 발동시킨다.
                "TRUNCATE settlement_journal CASCADE")) {
            assertThatThrownBy(() -> jdbc.execute(statement)).as(statement).hasMessageContaining(APPEND_ONLY);
        }
        // 외래 키가 걸린 settlement_journal 의 일반 TRUNCATE 는 PostgreSQL 이 트리거 전에 거절한다(두 번째 방어선).
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE settlement_journal"))
                .hasMessageContaining("cannot truncate a table referenced in a foreign key constraint");
        assertThat(count("settlement_journal")).isEqualTo(journals);
        assertThat(count("settlement_entry")).isEqualTo(entries);
        assertThat(sellerId).isNotNull();
    }

    @Test
    void sellerAllocationRowsAreAppendOnlyToo() {
        UUID checkoutId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        jdbc.update("""
                INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won,
                    created_at, updated_at)
                VALUES (?, 'guard-buyer', ?, 'RESERVED', 9000, now(), now())
                """, checkoutId, "guard-" + checkoutId);
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref,
                    state, checkout_expires_at, created_at, updated_at, order_source, checkout_key,
                    checkout_id, coupon_discount_won)
                VALUES (?, ?, NULL, NULL, 'guard-buyer', 'PAID', now() + INTERVAL '10 minutes', now(), now(),
                    'CART', ?, ?, 0)
                """, orderId, sellerId, "key-" + orderId, checkoutId);
        settlement.recordCheckoutCapture(new SettlementPostingRequest.CheckoutSettlement(checkoutId,
                UUID.randomUUID(), 9_000, List.of(new SettlementPostingRequest.SellerOrderAllocation(orderId, sellerId, 9_000))));

        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_seller_allocation SET amount_won = amount_won + 1"))
                .hasMessageContaining(APPEND_ONLY);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlement_seller_allocation"))
                .hasMessageContaining(APPEND_ONLY);
    }

    @Test
    void unbalancedJournalIsRejectedAtCommitAndLeavesNothing() {
        UUID attemptId = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            UUID journalId = insertCaptureJournal(attemptId, 500);
            insertEntry(journalId, "PSP_CLEARING", "DEBIT", 500);
            insertEntry(journalId, "SELLER_PAYABLE", "CREDIT", 499);
        })).hasMessageContaining(UNBALANCED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_journal WHERE source_attempt_id = ?", Long.class, attemptId)).isZero();
    }

    @Test
    void journalWithoutEntriesIsRejectedAtCommit() {
        UUID attemptId = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> insertCaptureJournal(attemptId, 500)))
                .hasMessageContaining(UNBALANCED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_journal WHERE source_attempt_id = ?", Long.class, attemptId)).isZero();
    }

    @Test
    void entryAddedLaterThatUnbalancesACommittedJournalIsRejected() {
        UUID attemptId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            UUID journalId = insertCaptureJournal(attemptId, 500);
            insertEntry(journalId, "PSP_CLEARING", "DEBIT", 500);
            insertEntry(journalId, "SELLER_PAYABLE", "CREDIT", 500);
        });
        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_attempt_id = ?", UUID.class, attemptId);
        assertThatThrownBy(() -> insertEntry(journalId, "SELLER_RESERVE", "CREDIT", 1)).hasMessageContaining(UNBALANCED);
    }

    @Test
    void replayedCaptureAndBalancedFlowsStillCommit() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        SettlementPostingRequest.Capture capture = new SettlementPostingRequest.Capture(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000);
        settlement.recordCapture(capture);
        // ON CONFLICT DO NOTHING 으로 건너뛴 재생은 새 분개가 없으므로 지연 검사도 없다.
        assertThatCode(() -> settlement.recordCapture(capture)).doesNotThrowAnyException();
        SettlementPostingRequest.Refund refund = new SettlementPostingRequest.Refund(
                UUID.randomUUID(), capture.orderId(), sellerId, 2_000);
        settlement.reserveRefund(refund);
        settlement.recordRefund(refund);
        assertThat(audits.audit().totalViolations()).isZero();
    }

    @Test
    void ownerCorrectsAnEntryInOneTransactionAndTheGuardIsBackAfterCommit() {
        UUID sellerId = capture(10_000);
        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE seller_id = ?", UUID.class, sellerId);
        // break-glass: 같은 트랜잭션에서 비활성화 -> 정정 -> 재활성화. 다른 세션은 커밋 전에 비활성 상태를 볼 수 없다.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE settlement_entry DISABLE TRIGGER settlement_entry_append_only");
            jdbc.execute("ALTER TABLE settlement_journal DISABLE TRIGGER settlement_journal_append_only");
            jdbc.update("UPDATE settlement_entry SET amount_won = 7500 WHERE journal_id = ? AND account_code = 'SELLER_PAYABLE'",
                    journalId);
            jdbc.update("UPDATE settlement_entry SET amount_won = 1500 WHERE journal_id = ? AND account_code = 'PLATFORM_FEE'",
                    journalId);
            jdbc.update("UPDATE settlement_journal SET fee_amount_won = 1500, fee_rate_bps = 1500 WHERE journal_id = ?",
                    journalId);
            jdbc.execute("ALTER TABLE settlement_journal ENABLE TRIGGER settlement_journal_append_only");
            jdbc.execute("ALTER TABLE settlement_entry ENABLE TRIGGER settlement_entry_append_only");
        });
        assertThat(jdbc.queryForObject(
                "SELECT amount_won FROM settlement_entry WHERE journal_id = ? AND account_code = 'PLATFORM_FEE'",
                Long.class, journalId)).isEqualTo(1500L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger WHERE tgname IN ('settlement_entry_append_only',
                    'settlement_journal_append_only') AND tgenabled = 'O'
                """, Long.class)).isEqualTo(2L);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_entry SET amount_won = 1 WHERE journal_id = ?", journalId))
                .hasMessageContaining(APPEND_ONLY);
        // 정정이 두 항목과 분개 스냅샷을 함께 고쳤으므로 점검은 0건이다.
        assertThat(audits.audit().totalViolations()).isZero();
    }

    @Test
    void documentedRollbackScriptRestoresThePreV7Behavior() throws IOException {
        UUID sellerId = capture(10_000);
        String undo = Files.readString(ROLLBACK_SCRIPT);
        transactions.executeWithoutResult(status -> {
            jdbc.execute(undo);
            // 롤백 뒤에는 수정과 불균형 커밋이 다시 가능하다(이 트랜잭션은 되돌린다).
            assertThat(jdbc.update("UPDATE settlement_entry SET amount_won = amount_won WHERE seller_id = ?", sellerId))
                    .isPositive();
            UUID journalId = insertCaptureJournal(UUID.randomUUID(), 5);
            insertEntry(journalId, "PSP_CLEARING", "DEBIT", 5);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            status.setRollbackOnly();
        });
        // 롤백했으므로 가드는 여전히 살아 있다.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlement_entry WHERE seller_id = ?", sellerId))
                .hasMessageContaining(APPEND_ONLY);
    }

    private UUID capture(long grossWon) {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, grossWon));
        return sellerId;
    }

    private UUID insertCaptureJournal(UUID attemptId, long grossWon) {
        return jdbc.queryForObject("""
                INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency,
                    gross_amount_won, recorded_at)
                VALUES (?, ?, ?, 'KRW', ?, now()) RETURNING journal_id
                """, UUID.class, UUID.randomUUID(), attemptId, UUID.randomUUID(), grossWon);
    }

    private void insertEntry(UUID journalId, String account, String side, long amountWon) {
        jdbc.update("""
                INSERT INTO settlement_entry (journal_id, account_code, entry_side, amount_won, recorded_at)
                VALUES (?, ?, ?, ?, now())
                """, journalId, account, side, amountWon);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
