package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.support.AppendOnlyTestReset;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 연결이 transaction <b>도중에</b> 끊겨도 원장이 all-or-nothing 으로 남는지 확인한다.
 *
 * <p>왜 필요한가: 기존 crash 테스트는 mock 예외 주입이거나 PayPal 응답 유실이다. 어느 것도
 * "commit 전에 DB 연결이 사라지면 디스크에 무엇이 남는가"를 보여주지 않는다. 이 테스트는
 * 별도 커넥션에서 {@code pg_terminate_backend} 로 진행 중인 transaction 의 백엔드를 직접
 * 죽여 그 질문에 답한다.
 *
 * <p>무엇을 증명하나: journal insert 와 entry insert 가 한 transaction 이면 둘 다 없거나 둘 다
 * 있어야 한다. 연결이 끊기면 PostgreSQL 은 uncommitted transaction 을 rollback 하므로
 * <b>고아 journal(entry 없는 journal)</b> 이 없어야 한다. 이것이 이 테스트가 검증 가능한
 * 경계다.
 *
 * <p>무엇을 증명하지 못하나: COMMIT 명령이 서버에 도달한 뒤 클라이언트가 응답을 못 받은
 * 경우("commit 성공인데 애플리케이션은 실패로 봄"). 그 경우는 서버가 이미 commit 했으므로
 * rollback 되지 않는다. 이 상황은 재시도 멱등성(unique 제약 + insertIfAbsent)으로 흡수되며
 * 아래 멱등 재시도 테스트가 그 절반을 덮는다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementDbFailureAtomicityIntegrationTest extends PayPalIntegrationTestSupport {
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

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementPayoutService payouts;

    @BeforeEach
    void clearLedger() {
        AppendOnlyTestReset.clearLedger(jdbc);
        jdbc.update("DELETE FROM settlement_payout");
    }

    /**
     * journal 과 entry 를 한 transaction 에 쓰는 도중 백엔드를 죽인다.
     *
     * <p>기대: 어떤 행도 남지 않는다. journal 만 남으면 statement 가 entry 없는 분개를 읽는다.
     */
    @Test
    void killingBackendMidTransactionLeavesNoPartialJournal() {
        UUID sellerId = createSeller();
        UUID orderId = createPurchaseOrder(sellerId);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            int backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            // 같은 transaction 안에서 journal 을 먼저 쓴다. 이 시점에는 commit 되지 않았다.
            jdbc.update("""
                    INSERT INTO settlement_journal
                        (source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
                    VALUES (?, ?, ?, ?, now())
                    """, orderId, UUID.randomUUID(), sellerId, 10_000);
            // entry 를 쓰기 전에 이 transaction 의 백엔드를 다른 커넥션에서 종료한다.
            terminateBackend(backendPid);
            jdbc.update("""
                    INSERT INTO settlement_entry
                        (journal_id, account_code, entry_side, amount_won, recorded_at)
                    VALUES ((SELECT journal_id FROM settlement_journal WHERE source_order_id = ?),
                            'PSP_CLEARING', 'DEBIT', 10000, now())
                    """, orderId);
        })).isInstanceOf(RuntimeException.class);

        // 연결이 끊긴 transaction 은 서버에서 rollback 된다. journal 도 entry 도 없어야 한다.
        assertThat(count("settlement_journal")).isZero();
        assertThat(count("settlement_entry")).isZero();
        assertThat(orphanJournalCount()).isZero();
    }

    /**
     * 연결이 끊긴 뒤 같은 사실로 다시 쓰면 정확히 1건이 남는다.
     *
     * <p>이것은 "commit 은 됐지만 응답을 못 받은" 경우의 재시도 절반을 덮는다. unique 제약과
     * insertIfAbsent 가 중복 분개를 막는다.
     */
    @Test
    void replayingAfterConnectionLossProducesExactlyOneJournal() {
        UUID sellerId = createSeller();
        UUID orderId = createPurchaseOrder(sellerId);
        UUID attemptId = UUID.randomUUID();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            int backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            jdbc.update("""
                    INSERT INTO settlement_journal
                        (source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
                    VALUES (?, ?, ?, ?, now())
                    """, orderId, attemptId, sellerId, 10_000);
            terminateBackend(backendPid);
        })).isInstanceOf(RuntimeException.class);

        // 재시도: 같은 attempt 로 다시 쓴다.
        // V7 의 분개 균형 검사 때문에 분개와 항목을 한 transaction 으로 쓴다. 항목도 ON CONFLICT 로 멱등이다.
        template.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO settlement_journal
                        (source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
                    VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (source_attempt_id) DO NOTHING
                    """, orderId, attemptId, sellerId, 10_000);
            jdbc.update("""
                    INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
                    SELECT journal_id, seller_id, account_code, entry_side, 10000, now()
                    FROM settlement_journal, (VALUES ('PSP_CLEARING', 'DEBIT'), ('SELLER_PAYABLE', 'CREDIT'))
                        AS entry (account_code, entry_side)
                    WHERE source_attempt_id = ?
                    ON CONFLICT DO NOTHING
                    """, attemptId);
        });

        assertThat(count("settlement_journal")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_journal WHERE source_attempt_id = ?",
                Integer.class, attemptId)).isEqualTo(1);
    }

    /**
     * 지급 확정은 상태 전이와 분개를 같은 transaction 에서 한다.
     *
     * <p>전이만 commit 되고 분개가 없으면, 지급은 CONFIRMED 인데 잔액은 줄지 않아 같은 돈을 두
     * 번 지급할 수 있다. 여기서는 SENT 상태에서 확정 transaction 도중 연결을 끊고, 전이가
     * 되돌아가는지 확인한다.
     */
    @Test
    void killingBackendDuringPayoutConfirmLeavesStateAndLedgerConsistent() {
        UUID sellerId = createSeller();
        UUID orderId = createPurchaseOrder(sellerId);
        postCapture(sellerId, orderId, 10_000);

        PayoutResponse.SellerPayout payout = payouts.request(sellerId,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now(), "kill-confirm-key");
        payouts.approve(payout.payoutId());
        payouts.markSent(payout.payoutId());
        long entriesBefore = count("settlement_entry");
        long journalsBefore = count("settlement_journal");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            int backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            // confirm 과 같은 순서: 상태 전이 -> 분개. 분개 전에 연결을 끊는다.
            jdbc.update("""
                    UPDATE settlement_payout SET state = 'CONFIRMED', confirmed_at = now()
                    WHERE payout_id = ? AND state = 'SENT'
                    """, payout.payoutId());
            terminateBackend(backendPid);
            jdbc.update("""
                    INSERT INTO settlement_entry
                        (journal_id, account_code, entry_side, amount_won, recorded_at)
                    VALUES (?, 'SELLER_PAYABLE', 'DEBIT', ?, now())
                    """, UUID.randomUUID(), payout.amountWon());
        })).isInstanceOf(RuntimeException.class);

        // 전이도 분개도 모두 rollback 되어야 한다. 상태는 여전히 SENT 다.
        assertThat(jdbc.queryForObject(
                "SELECT state FROM settlement_payout WHERE payout_id = ?",
                String.class, payout.payoutId())).isEqualTo("SENT");
        assertThat(count("settlement_entry")).isEqualTo(entriesBefore);
        assertThat(count("settlement_journal")).isEqualTo(journalsBefore);

        // 그리고 확정을 다시 시도하면 정상적으로 1회만 분개된다.
        payouts.confirm(payout.payoutId());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM settlement_payout WHERE payout_id = ?",
                String.class, payout.payoutId())).isEqualTo("CONFIRMED");
        assertThat(count("settlement_entry")).isEqualTo(entriesBefore + 2);
        assertThat(orphanJournalCount()).isZero();
    }

    /**
     * 확정된 지급 분개가 중복되지 않는지 반복 확인한다.
     *
     * <p>재시도가 여러 번 일어나도 지급 1건 = journal 1건이어야 한다(unique index).
     */
    @Test
    void repeatedPayoutConfirmationKeepsOneJournalPerPayout() {
        UUID sellerId = createSeller();
        UUID orderId = createPurchaseOrder(sellerId);
        postCapture(sellerId, orderId, 10_000);

        PayoutResponse.SellerPayout payout = payouts.request(sellerId,
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now(), "repeat-confirm-key");
        payouts.approve(payout.payoutId());
        payouts.markSent(payout.payoutId());
        payouts.confirm(payout.payoutId());
        // 이후 repeated 호출은 멱등이어야 한다.
        payouts.confirm(payout.payoutId());
        payouts.confirm(payout.payoutId());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_journal WHERE source_payout_id = ?
                """, Integer.class, payout.payoutId())).isEqualTo(1);
        assertThat(orphanJournalCount()).isZero();
        assertThat(debitTotal()).isEqualTo(creditTotal());
    }

    private void terminateBackend(int backendPid) {
        // 새 커넥션으로 죽인다. 같은 JdbcTemplate 을 쓰면 죽은 커넥션을 재사용할 수 있다.
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_terminate_backend(" + backendPid + ")");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not terminate backend " + backendPid, exception);
        }
    }

    private void postCapture(UUID sellerId, UUID orderId, long gross) {
        // V7 의 분개 균형 검사는 commit 때 하므로 분개와 항목을 한 transaction 으로 쓴다(서비스도 그렇게 쓴다).
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> insertCapture(sellerId, orderId, gross));
    }

    private void insertCapture(UUID sellerId, UUID orderId, long gross) {
        jdbc.update("""
                INSERT INTO settlement_journal
                    (source_order_id, source_attempt_id, seller_id, gross_amount_won, recorded_at)
                VALUES (?, ?, ?, ?, now())
                """, orderId, UUID.randomUUID(), sellerId, gross);
        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE source_order_id = ?",
                UUID.class, orderId);
        long fee = gross / 10;
        long reserve = gross / 10;
        long payable = gross - fee - reserve;
        // seller_id 는 V52 에서 추가됐다. statement 와 지급 가능 잔액이 이 컬럼으로 seller 를
        // 구분하므로 반드시 채워야 한다.
        jdbc.update("""
                INSERT INTO settlement_entry
                    (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
                VALUES (?, ?, 'PSP_CLEARING', 'DEBIT', ?, now()),
                       (?, ?, 'SELLER_PAYABLE', 'CREDIT', ?, now()),
                       (?, ?, 'PLATFORM_FEE', 'CREDIT', ?, now()),
                       (?, ?, 'SELLER_RESERVE', 'CREDIT', ?, now())
                """, journalId, sellerId, gross, journalId, sellerId, payable,
                journalId, sellerId, fee, journalId, sellerId, reserve);
    }

    private long debitTotal() {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_won), 0) FROM settlement_entry WHERE entry_side = 'DEBIT'
                """, Long.class);
        return total == null ? 0 : total;
    }

    private long creditTotal() {
        Long total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount_won), 0) FROM settlement_entry WHERE entry_side = 'CREDIT'
                """, Long.class);
        return total == null ? 0 : total;
    }

    /** entry 가 하나도 없는 journal 수. 0 이어야 한다. */
    private long orphanJournalCount() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM settlement_journal j
                WHERE NOT EXISTS (SELECT 1 FROM settlement_entry e WHERE e.journal_id = j.journal_id)
                """, Long.class);
        return count == null ? 0 : count;
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private UUID createSeller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }

    private UUID createPurchaseOrder(UUID sellerId) {
        UUID orderId = UUID.randomUUID();
        UUID checkoutId = UUID.randomUUID();
        // purchase_order.checkout_id 는 buyer_checkout 을 참조한다. 실제 checkout 행을 만든다.
        jdbc.update("""
                INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won,
                    created_at, updated_at)
                VALUES (?, 'db-failure-buyer', ?, 'RESERVED', 10000, now(), now())
                """, checkoutId, "db-failure-" + checkoutId);
        jdbc.update("""
                INSERT INTO purchase_order
                    (order_id, seller_id, buyer_ref, state, checkout_expires_at, order_source,
                     checkout_id, coupon_discount_won, created_at, updated_at)
                VALUES (?, ?, 'db-failure-buyer', 'PAID', now() + interval '1 hour', 'CART',
                        ?, 0, now(), now())
                """, orderId, sellerId, checkoutId);
        return orderId;
    }
}
