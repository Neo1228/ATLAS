package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.service.SettlementBalanceCheckpointService;
import com.atlas.settlement.service.SettlementService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V10·EXP-115: 판매자 정산서 기간 질의와 판매자 락 안의 잔액 읽기가 판매자 원장 이력 크기와 무관하게 읽는지를
 * 실제 PostgreSQL에서 확인한다.
 *
 * <p>같은 판매자에게 400일 전 이력을 N건, 4N건 쌓은 두 시점에서 저장소가 실제로 실행하는 SQL(기록용
 * JdbcTemplate으로 잡은 문장과 인자 그대로)을 {@code EXPLAIN (ANALYZE, BUFFERS)}로 돌려 읽은 버퍼를 비교한다.
 * 이력에 비례하면 4N에서 버퍼가 약 4배가 되고, 기간·결과 크기에만 비례하면 거의 같다.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SellerLedgerBoundedReadIntegrationTest extends PayPalIntegrationTestSupport {
    private static final int HISTORY = 400;

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
        registry.add("atlas.settlement.fee-rate-bps", () -> "300");
        registry.add("atlas.settlement.reserve-rate-bps", () -> "500");
    }

    @Autowired private SettlementService settlement;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionTemplate transactions;
    @Autowired private SettlementBalanceCheckpointService checkpoints;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void checkoutAllocationTakesItsJournalRecordedAtEvenWhenTheWriterPassesAnotherValue() {
        UUID sellerA = seller();
        UUID sellerB = seller();
        UUID checkoutId = checkout(sellerA, sellerB);
        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM settlement_journal WHERE checkout_id = ?", UUID.class, checkoutId);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_seller_allocation a JOIN settlement_journal j USING (journal_id)
                WHERE a.journal_id = ? AND a.recorded_at = j.recorded_at
                """, Long.class, journalId)).isEqualTo(2);

        // 앱이 다른 값을 넘겨도 분개 값으로 덮는다(배분 날짜의 원천은 분개 하나).
        UUID sellerC = seller();
        UUID orderC = order(checkoutId, sellerC);
        Instant stored = transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO settlement_seller_allocation (journal_id, order_id, seller_id, amount_won,
                        allocation_ordinal, recorded_at)
                    VALUES (?, ?, ?, 1, 9, TIMESTAMPTZ '2000-01-01 00:00:00+00')
                    """, journalId, orderC, sellerC);
            Instant value = jdbc.queryForObject("SELECT recorded_at FROM settlement_seller_allocation "
                    + "WHERE journal_id = ? AND seller_id = ?", Timestamp.class, journalId, sellerC).toInstant();
            status.setRollbackOnly();
            return value;
        });
        assertThat(stored).isEqualTo(jdbc.queryForObject(
                "SELECT recorded_at FROM settlement_journal WHERE journal_id = ?", Timestamp.class, journalId).toInstant());
    }

    @Test
    void statementPeriodBoundsUseTheAllocationRecordedAtWithHalfOpenInterval() {
        UUID sellerA = seller();
        UUID sellerB = seller();
        UUID checkoutId = checkout(sellerA, sellerB);
        Instant at = jdbc.queryForObject(
                "SELECT recorded_at FROM settlement_journal WHERE checkout_id = ?", Timestamp.class, checkoutId).toInstant();
        SettlementJournalRepository journals = new SettlementJournalRepository(jdbc);
        Instant justAfter = at.plus(1, ChronoUnit.MICROS);

        assertThat(journals.sellerStatementRows(sellerA, at, justAfter, null, null, 10)).hasSize(1);
        assertThat(journals.sellerStatementRows(sellerA, at.minusSeconds(60), at, null, null, 10)).isEmpty();
        var inside = journals.sellerStatementTotals(sellerA, at, justAfter);
        var before = journals.sellerStatementTotals(sellerA, at.minusSeconds(60), at);
        // 판매자 A 몫 10,000원: 수수료 3% 300, 보류 5% 500
        assertThat(inside.capturedAmountWon()).hasToString("10000");
        assertThat(inside.feeAmountWon()).hasToString("300");
        assertThat(inside.reserveAmountWon()).hasToString("500");
        assertThat(before.capturedAmountWon()).hasToString("0");
        assertThat(before.feeAmountWon()).hasToString("0");
        assertThat(before.reserveAmountWon()).hasToString("0");
    }

    @Test
    void statementPeriodReadsDoNotGrowWithTheSellersHistory() {
        UUID seller = seller();
        UUID other = seller();
        checkout(seller, other);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), seller, 20_000));
        Instant to = Instant.now().plus(Duration.ofDays(1));
        Instant from = to.minus(Duration.ofDays(31));

        seedOldHistory(seller, other, HISTORY);
        long totalsSmall = buffers(repo -> repo.sellerStatementTotals(seller, from, to));
        long rowsSmall = buffers(repo -> repo.sellerStatementRows(seller, from, to, null, null, 21));
        seedOldHistory(seller, other, 3 * HISTORY);
        long totalsLarge = buffers(repo -> repo.sellerStatementTotals(seller, from, to));
        long rowsLarge = buffers(repo -> repo.sellerStatementRows(seller, from, to, null, null, 21));

        System.out.println("BOUNDED-READ totals " + totalsSmall + " -> " + totalsLarge
                + ", rows " + rowsSmall + " -> " + rowsLarge + " (history " + HISTORY + " -> " + 4 * HISTORY + ")");
        // 이력 4배에서 읽는 버퍼가 1.5배 + 인덱스 깊이 여유(20) 안이어야 한다. 이력 비례면 약 4배다.
        assertThat(totalsLarge).isLessThanOrEqualTo(totalsSmall * 3 / 2 + 20);
        assertThat(rowsLarge).isLessThanOrEqualTo(rowsSmall * 3 / 2 + 20);
    }

    @Test
    void sellerBalanceInsideTheSellerLockDoesNotGrowWithHistoryOnceFolded() {
        UUID small = seller();
        UUID large = seller();
        UUID other = seller();
        seedOldHistory(small, other, HISTORY);
        seedOldHistory(large, other, 4 * HISTORY);
        foldAll();
        // 마감 뒤 꼬리: 두 판매자 모두 capture 1건
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), small, 20_000));
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), large, 20_000));

        long smallBuffers = buffers(repo -> repo.sellerBalance(small));
        long largeBuffers = buffers(repo -> repo.sellerBalance(large));
        System.out.println("BOUNDED-READ balance history " + HISTORY + " -> " + smallBuffers + " buffers, "
                + 4 * HISTORY + " -> " + largeBuffers + " buffers");
        assertThat(largeBuffers).isLessThanOrEqualTo(smallBuffers * 3 / 2 + 20);
        assertThat(balance(small)).isEqualTo(ledgerSum(small));
        assertThat(balance(large)).isEqualTo(ledgerSum(large));
    }

    @Test
    void checkpointPlusTailEqualsTheLedgerAcrossFoldsAndALateCommittingWriter() throws Exception {
        UUID seller = seller();
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), seller, 30_000));
        foldAll();
        assertThat(balance(seller)).isEqualTo(ledgerSum(seller));

        // 다른 연결이 항목을 넣고 커밋하지 않은 채로 마감이 돈다. 그 항목은 커서 아래로 들어가면 안 된다.
        try (Connection writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (var statement = writer.prepareStatement("""
                    WITH j AS (
                        INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency,
                            gross_amount_won, recorded_at)
                        VALUES (gen_random_uuid(), gen_random_uuid(), ?, 'KRW', 7000, now()) RETURNING journal_id)
                    INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
                    SELECT journal_id, ?, x.account_code, x.side, 7000, now() FROM j
                    CROSS JOIN (VALUES ('PSP_CLEARING', 'DEBIT'), ('SELLER_PAYABLE', 'CREDIT')) x(account_code, side)
                    """)) {
                statement.setObject(1, seller);
                statement.setObject(2, seller);
                statement.executeUpdate();
            }
            Thread.sleep(20);
            settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), seller, 11_000));
            foldAll();
            writer.commit();
        }
        foldAll();
        assertThat(balance(seller)).isEqualTo(ledgerSum(seller));
        assertThat(jdbc.queryForObject("SELECT payable_won FROM settlement_seller_balance_checkpoint WHERE seller_id = ?",
                Long.class, seller)).isEqualTo(ledgerSum(seller).get(0));
    }

    private void foldAll() {
        while (checkpoints.foldOnce(100_000, Duration.ZERO)) {
            // 따라잡을 때까지
        }
    }

    private List<Long> balance(UUID seller) {
        var value = new SettlementJournalRepository(jdbc).sellerBalance(seller);
        return List.of(value.payableAmountWon().longValueExact(), value.reserveAmountWon().longValueExact(),
                value.feeAmountWon().longValueExact());
    }

    private List<Long> ledgerSum(UUID seller) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN account_code = 'SELLER_PAYABLE'
                           THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN account_code = 'SELLER_RESERVE'
                           THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN account_code = 'PLATFORM_FEE'
                           THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0)
                FROM settlement_entry WHERE seller_id = ?
                """, (rs, n) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), seller);
    }

    /** 저장소 호출 하나가 실행한 SQL들을 그 인자 그대로 EXPLAIN (ANALYZE, BUFFERS)로 다시 돌려 읽은 버퍼 합을 돌려준다. */
    private long buffers(Consumer<SettlementJournalRepository> call) {
        jdbc.execute("ANALYZE settlement_journal, settlement_entry, settlement_seller_allocation, "
                + "settlement_seller_balance_checkpoint");
        RecordingJdbcTemplate recorder = new RecordingJdbcTemplate(dataSource);
        call.accept(new SettlementJournalRepository(recorder));
        assertThat(recorder.statements).isNotEmpty();
        long total = 0;
        for (Recorded statement : recorder.statements) {
            long last = 0;
            for (int run = 0; run < 2; run++) {
                String plan = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement.sql(),
                        String.class, statement.args());
                JsonNode top = json.readTree(plan).get(0).get("Plan");
                last = top.get("Shared Hit Blocks").asLong() + top.get("Shared Read Blocks").asLong();
            }
            total += last;
        }
        return total;
    }

    /**
     * 400일 전부터 1분 간격으로 capture 분개와 checkout 배분을 판매자에게 count 건씩 쌓는다(recorded_at 만 과거, id 는
     * 지금 만든 UUIDv7 이라 잔액 마감 커서 위에 놓인다. 운영에서도 새 항목의 id 는 언제나 지금 시각이다). 외래 키 대상(주문·
     * checkout)을 만들지 않으려고 복제 역할로 넣으며, 그래서 배분 날짜 트리거도 돌지 않으므로 분개 값을 직접 넣는다.
     */
    private void seedOldHistory(UUID seller, UUID other, int count) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    WITH g AS (
                        SELECT i, now() - interval '400 days' - i * interval '1 minute' AS at,
                               uuidv7() AS capture_id, uuidv7() AS checkout_journal
                        FROM generate_series(1, ?) i),
                    journals AS (
                        INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, checkout_id,
                            seller_id, currency, gross_amount_won, recorded_at, fee_amount_won, reserve_amount_won)
                        SELECT capture_id, gen_random_uuid(), gen_random_uuid(), NULL::uuid, ?::uuid, 'KRW', 10000, at,
                               300, 500 FROM g
                        UNION ALL
                        SELECT checkout_journal, NULL, gen_random_uuid(), gen_random_uuid(), NULL, 'KRW', 15000, at,
                               0, 0 FROM g
                        RETURNING journal_id),
                    allocations AS (
                        INSERT INTO settlement_seller_allocation (journal_id, order_id, seller_id, amount_won,
                            allocation_ordinal, recorded_at)
                        SELECT checkout_journal, gen_random_uuid(), ?::uuid, 10000, 0, at FROM g
                        UNION ALL
                        SELECT checkout_journal, gen_random_uuid(), ?::uuid, 5000, 1, at FROM g
                        RETURNING journal_id)
                    INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won, recorded_at)
                    SELECT j, s, e.account_code, e.side, e.amount, at
                    FROM (SELECT capture_id AS j, ?::uuid AS s, 10000 AS gross, at FROM g
                          UNION ALL SELECT checkout_journal, ?::uuid, 10000, at FROM g
                          UNION ALL SELECT checkout_journal, ?::uuid, 5000, at FROM g) x
                    CROSS JOIN LATERAL (VALUES
                        ('PSP_CLEARING', 'DEBIT', x.gross),
                        ('SELLER_PAYABLE', 'CREDIT', x.gross - x.gross * 8 / 100),
                        ('PLATFORM_FEE', 'CREDIT', x.gross * 3 / 100),
                        ('SELLER_RESERVE', 'CREDIT', x.gross * 5 / 100)) e(account_code, side, amount)
                    """, count, seller, seller, other, seller, seller, other);
        });
    }

    private UUID checkout(UUID sellerA, UUID sellerB) {
        UUID checkoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO buyer_checkout (checkout_id, buyer_ref, idempotency_key, state, total_won,
                    created_at, updated_at)
                VALUES (?, 'bounded-read-buyer', ?, 'RESERVED', 25000, now(), now())
                """, checkoutId, "bounded-" + checkoutId);
        UUID orderA = order(checkoutId, sellerA);
        UUID orderB = order(checkoutId, sellerB);
        settlement.recordCheckoutCapture(new SettlementPostingRequest.CheckoutSettlement(checkoutId,
                UUID.randomUUID(), 25_000, List.of(
                        new SettlementPostingRequest.SellerOrderAllocation(orderA, sellerA, 10_000),
                        new SettlementPostingRequest.SellerOrderAllocation(orderB, sellerB, 15_000))));
        return checkoutId;
    }

    private UUID seller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }

    private UUID order(UUID checkoutId, UUID sellerId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_order (order_id, seller_id, campaign_id, ticket_id, buyer_ref,
                    state, checkout_expires_at, created_at, updated_at, order_source, checkout_key,
                    checkout_id, coupon_discount_won)
                VALUES (?, ?, NULL, NULL, 'bounded-read-buyer', 'PAID', now() + INTERVAL '10 minutes', now(), now(),
                    'CART', ?, ?, 0)
                """, orderId, sellerId, "key-" + orderId, checkoutId);
        return orderId;
    }

    private record Recorded(String sql, Object[] args) {
    }

    /** 저장소가 실행하는 SQL과 인자를 그대로 기록한다(실행도 한다). */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Recorded> statements = new ArrayList<>();

        RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            statements.add(new Recorded(sql, args));
            return super.query(sql, rowMapper, args);
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            statements.add(new Recorded(sql, args));
            return super.queryForObject(sql, rowMapper, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            statements.add(new Recorded(sql, args));
            return super.queryForObject(sql, requiredType, args);
        }
    }
}
