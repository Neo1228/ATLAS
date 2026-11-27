package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.repository.SettlementBalanceCheckpointRepository;
import com.atlas.settlement.repository.SettlementJournalRepository;
import com.atlas.settlement.service.SettlementBalanceCheckpointService;
import com.atlas.settlement.service.SettlementService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private static final int STATEMENT_HISTORY = 50;
    private static final int OLD_PLATFORM_CHECKOUTS = 50_000;
    private static final int PERIOD_PLATFORM_CHECKOUTS = 20_000;
    private static final int SCATTER_FILLER = 99;
    private static final String FOLD_ROLE = "ledger_fold_probe";
    private static final String WRITER_ROLE = "ledger_writer_probe";
    private static final String PROBE_PASSWORD = "probe";

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
        // 마감은 시험이 직접 돌린다(worker 가 같은 커서를 잡으면 시험의 마감 순서가 흔들린다).
        registry.add("atlas.settlement.balance-checkpoint.enabled", () -> "false");
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
    void statementRowsDoNotReadTheSellersScatteredCheckoutHistory() {
        UUID seller = seller();
        UUID other = seller();
        checkout(seller, other);
        Instant to = Instant.now().plus(Duration.ofDays(1));
        Instant from = to.minus(Duration.ofDays(31));
        // 변이 M3(checkout 배분을 분개 날짜로 자름)는 판매자 배분이 운영처럼 표 곳곳에 흩어져 있어야 드러난다. 한꺼번에
        // 넣은 이력은 몇 페이지에 몰려 판매자 배분 전체를 읽어도 싸다. 그래서 판매자 checkout 배분 한 건마다 다른 판매자
        // 배분 99건을 사이에 넣고(EXP-115 100k 판매자: 배분 18,000건이 흩어짐), 기간 안 플랫폼 checkout 을 쌓아 분개 날짜
        // 경로도 싸지 않게 한다. 기간 밖 플랫폼 checkout 은 기간 비율을 운영 모양(작은 몫)에 가깝게 해 올바른 질의의
        // 계획이 추정 오차로 흔들리지 않게 한다.
        seedPlatformCheckouts(OLD_PLATFORM_CHECKOUTS, Duration.ofDays(31), Duration.ofDays(730));
        seedPlatformCheckouts(PERIOD_PLATFORM_CHECKOUTS, Duration.ZERO, Duration.ofDays(30));

        seedScatteredCheckoutHistory(seller, STATEMENT_HISTORY);
        long rowsSmall = buffers(repo -> repo.sellerStatementRows(seller, from, to, null, null, 21));
        seedScatteredCheckoutHistory(seller, 3 * STATEMENT_HISTORY);
        long rowsLarge = buffers(repo -> repo.sellerStatementRows(seller, from, to, null, null, 21));

        System.out.println("BOUNDED-READ scattered rows " + rowsSmall + " -> " + rowsLarge + " (checkout history "
                + STATEMENT_HISTORY + " -> " + 4 * STATEMENT_HISTORY + ", platform checkouts " + OLD_PLATFORM_CHECKOUTS
                + " old / " + PERIOD_PLATFORM_CHECKOUTS + " in period)");
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
    void foldNeverPassesAnUncommittedEntryOfAWriterUnderAnotherRole() throws Exception {
        probeRoles();
        UUID seller = seller();
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), seller, 30_000));
        foldAll();
        assertThat(balance(seller)).isEqualTo(ledgerSum(seller));

        // 다른 DB 역할로 로그인한 쓰기가 항목을 넣고 커밋하지 않은 채로, 앱 역할이 아닌 마감 역할(pg_stat_activity 에서
        // 그 세션의 xact_start 를 볼 수 없음)로 마감이 여러 번 돈다. 그 항목은 커서 아래로 들어가면 안 된다.
        try (Connection writer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), WRITER_ROLE, PROBE_PASSWORD)) {
            writer.setAutoCommit(false);
            UUID uncommitted;
            try (var statement = writer.prepareStatement("""
                    WITH j AS (
                        INSERT INTO settlement_journal (source_order_id, source_attempt_id, seller_id, currency,
                            gross_amount_won, recorded_at)
                        VALUES (gen_random_uuid(), gen_random_uuid(), ?, 'KRW', 7000, now()) RETURNING journal_id),
                    e AS (
                        INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won,
                            recorded_at)
                        SELECT journal_id, ?, x.account_code, x.side, 7000, now() FROM j
                        CROSS JOIN (VALUES ('PSP_CLEARING', 'DEBIT'), ('SELLER_PAYABLE', 'CREDIT')) x(account_code, side)
                        RETURNING entry_id)
                    SELECT entry_id FROM e ORDER BY entry_id LIMIT 1
                    """)) {
                statement.setObject(1, seller);
                statement.setObject(2, seller);
                try (var result = statement.executeQuery()) {
                    result.next();
                    uncommitted = result.getObject(1, UUID.class);
                }
            }
            awaitClockPast(uncommitted);
            settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), seller, 11_000));
            for (int round = 0; round < 4; round++) {
                foldOnceAs(FOLD_ROLE);
            }
            assertThat(jdbc.queryForObject("SELECT entry_id <= ?::uuid FROM settlement_balance_checkpoint_cursor",
                    Boolean.class, uncommitted)).as("cursor stays at or below the uncommitted entry").isTrue();
            writer.commit();
        }
        foldAll();
        assertThat(balance(seller)).isEqualTo(ledgerSum(seller));
        assertThat(jdbc.queryForObject("SELECT payable_won FROM settlement_seller_balance_checkpoint WHERE seller_id = ?",
                Long.class, seller)).isEqualTo(ledgerSum(seller).get(0));
    }

    @Test
    void anEntryThatIsItsTransactionsFirstWriteHasItsXidBeforeItsIdClock() throws Exception {
        probeRoles();
        UUID seller = seller();
        UUID journalId = UUID.randomUUID();
        // 항목만 넣는 쓰기(분개가 이미 있음)가 트랜잭션의 첫 쓰기다. 분개는 복제 역할로 미리 넣어 균형 검사를 건너뛴다.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, seller_id, currency,
                        gross_amount_won, recorded_at)
                    VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, 'KRW', 5000, now())
                    """, journalId, seller);
        });
        // 시험 전용 BEFORE INSERT 트리거: 기본값(entry_id)이 계산된 뒤, heap 삽입 전에 advisory 잠금에서 멈춘다.
        // 멈추는 첫 행은 잔액 계정(SELLER_PAYABLE)이어야 그 행을 잃었을 때 잔액이 원장과 달라진다.
        jdbc.execute("""
                CREATE FUNCTION pause_entry_insert_probe() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF current_setting('atlas.pause_entry_insert_probe', true) = 'on' THEN
                        PERFORM pg_advisory_lock(723723);
                        PERFORM pg_advisory_unlock(723723);
                    END IF;
                    RETURN NEW;
                END;
                $$""");
        jdbc.execute("CREATE TRIGGER pause_entry_insert_probe BEFORE INSERT ON settlement_entry "
                + "FOR EACH ROW EXECUTE FUNCTION pause_entry_insert_probe()");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection gate = dataSource.getConnection()) {
            try (var lock = gate.createStatement()) {
                lock.execute("SELECT pg_advisory_lock(723723)");
            }
            Future<?> writing = executor.submit(() -> {
                try (Connection writer = DriverManager.getConnection(POSTGRES.getJdbcUrl(), WRITER_ROLE, PROBE_PASSWORD)) {
                    writer.setAutoCommit(false);
                    try (var statement = writer.prepareStatement("""
                            INSERT INTO settlement_entry (journal_id, seller_id, account_code, entry_side, amount_won,
                                recorded_at)
                            SELECT ?, ?, x.account_code, x.side, 5000, now()
                            FROM (VALUES (1, 'SELLER_PAYABLE', 'CREDIT'), (2, 'PSP_CLEARING', 'DEBIT'))
                                x(ordinal, account_code, side)
                            ORDER BY x.ordinal
                            """)) {
                        try (var pause = writer.createStatement()) {
                            pause.execute("SET LOCAL atlas.pause_entry_insert_probe = 'on'");
                        }
                        statement.setObject(1, journalId);
                        statement.setObject(2, seller);
                        statement.executeUpdate();
                    }
                    writer.commit();
                }
                return null;
            });
            Timestamp paused = awaitWriterPaused();
            awaitClockPast(paused.toInstant());
            for (int round = 0; round < 4; round++) {
                checkpoints.foldOnce(100_000, Duration.ZERO);
            }
            try (var unlock = gate.createStatement()) {
                unlock.execute("SELECT pg_advisory_unlock(723723)");
            }
            writing.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            jdbc.execute("DROP TRIGGER IF EXISTS pause_entry_insert_probe ON settlement_entry");
            jdbc.execute("DROP FUNCTION IF EXISTS pause_entry_insert_probe()");
        }
        foldAll();
        assertThat(balance(seller)).isEqualTo(ledgerSum(seller));
        assertThat(jdbc.queryForObject("SELECT payable_won FROM settlement_seller_balance_checkpoint WHERE seller_id = ?",
                Long.class, seller)).isEqualTo(ledgerSum(seller).get(0));
    }

    @Test
    void documentedRollbackScriptRemovesTheV10ObjectsAndKeepsLedgerRows() throws Exception {
        UUID sellerA = seller();
        UUID sellerB = seller();
        checkout(sellerA, sellerB);
        long allocations = jdbc.queryForObject("SELECT count(*) FROM settlement_seller_allocation", Long.class);
        String undo = Files.readString(Path.of("..", "scripts", "db", "rollback",
                "V10__undo_seller_ledger_history_bounded_reads.sql").toAbsolutePath().normalize());
        transactions.executeWithoutResult(status -> {
            jdbc.execute(undo);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'settlement_seller_allocation' AND column_name = 'recorded_at'
                    """, Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT to_regclass('settlement_seller_balance_checkpoint') IS NULL",
                    Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT to_regclass('settlement_seller_allocation_seller_journal_idx') IS NOT NULL",
                    Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_seller_allocation", Long.class))
                    .isEqualTo(allocations);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT to_regclass('settlement_seller_balance_checkpoint') IS NOT NULL",
                Boolean.class)).isTrue();
    }

    /** 마감이 이 호출 시각 전에 커밋된 항목을 모두 접을 때까지 마감을 돌린다(시간 상한 안에서 조건을 기다림). */
    private void foldAll() {
        UUID target = SettlementBalanceCheckpointRepository.uuidV7Floor(
                jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!jdbc.queryForObject("SELECT entry_id >= ?::uuid FROM settlement_balance_checkpoint_cursor",
                Boolean.class, target)) {
            assertThat(System.nanoTime()).as("balance checkpoint caught up").isLessThan(deadline);
            checkpoints.foldOnce(100_000, Duration.ZERO);
        }
    }

    /** 앱 연결이지만 마감 트랜잭션 안에서만 슈퍼유저가 아닌 마감 역할로 돈다(pg_stat_activity 가 다른 역할을 가림). */
    private void foldOnceAs(String role) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            checkpoints.foldOnce(100_000, Duration.ZERO);
        });
    }

    private void probeRoles() {
        jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_fold_probe') THEN
                        CREATE ROLE ledger_fold_probe NOLOGIN;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_writer_probe') THEN
                        CREATE ROLE ledger_writer_probe LOGIN PASSWORD 'probe';
                    END IF;
                END;
                $$""");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO ledger_fold_probe, ledger_writer_probe");
    }

    /** DB 시계가 이 항목 id 의 밀리초를 2ms 넘을 때까지 기다린다(마감 경계가 밀리초 하한이라 같은 밀리초를 피함). */
    private void awaitClockPast(UUID entryId) {
        awaitClockPast(jdbc.queryForObject("SELECT uuid_extract_timestamp(?::uuid)", Timestamp.class, entryId).toInstant());
    }

    private void awaitClockPast(Instant at) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!jdbc.queryForObject("SELECT clock_timestamp() >= ?::timestamptz + interval '2 milliseconds'",
                Boolean.class, Timestamp.from(at))) {
            assertThat(System.nanoTime()).isLessThan(deadline);
        }
    }

    /** 쓰기 연결이 시험 트리거의 advisory 잠금을 기다리기 시작한 DB 시각. */
    private Timestamp awaitWriterPaused() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            Timestamp at = jdbc.query("""
                    SELECT clock_timestamp() FROM pg_locks
                    WHERE locktype = 'advisory' AND objid = 723723 AND NOT granted
                    LIMIT 1
                    """, (rs, n) -> rs.getTimestamp(1)).stream().findFirst().orElse(null);
            if (at != null) {
                return at;
            }
            assertThat(System.nanoTime()).as("writer paused before its heap insert").isLessThan(deadline);
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

    /** 지금 − newest 부터 지금 − oldest 까지 고르게 다른 판매자들의 checkout 분개와 배분을 count 건 넣는다(복제 역할). */
    private void seedPlatformCheckouts(int count, Duration newest, Duration oldest) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    WITH g AS (
                        SELECT now() - make_interval(secs => ? + i * ? / ?) AS at, uuidv7() AS journal_id
                        FROM generate_series(1, ?) i),
                    journals AS (
                        INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, checkout_id,
                            seller_id, currency, gross_amount_won, recorded_at, fee_amount_won, reserve_amount_won)
                        SELECT journal_id, NULL, gen_random_uuid(), gen_random_uuid(), NULL, 'KRW', 10000, at, 0, 0
                        FROM g
                        RETURNING journal_id)
                    INSERT INTO settlement_seller_allocation (journal_id, order_id, seller_id, amount_won,
                        allocation_ordinal, recorded_at)
                    SELECT journal_id, gen_random_uuid(), gen_random_uuid(), 10000, 0, at FROM g
                    """, (double) newest.toSeconds(), (double) (oldest.toSeconds() - newest.toSeconds()), (double) count,
                    count);
        });
    }

    /**
     * 400일 전 판매자 checkout 배분 count 건을, 한 건마다 다른 판매자의 checkout 분개·배분 SCATTER_FILLER 건을 사이에
     * 두고 같은 문장에서 넣는다(삽입 순서대로 heap 에 놓여 판매자 배분이 페이지마다 흩어진다).
     */
    private void seedScatteredCheckoutHistory(UUID seller, int count) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    WITH g AS (
                        SELECT i, now() - interval '400 days' - i * interval '1 minute' AS at, uuidv7() AS journal_id
                        FROM generate_series(1, ?) i),
                    x AS (
                        SELECT i, 0 AS k, journal_id, at FROM g
                        UNION ALL
                        SELECT g.i, k, uuidv7(), g.at - k * interval '1 second'
                        FROM g CROSS JOIN generate_series(1, ?) k),
                    journals AS (
                        INSERT INTO settlement_journal (journal_id, source_order_id, source_attempt_id, checkout_id,
                            seller_id, currency, gross_amount_won, recorded_at, fee_amount_won, reserve_amount_won)
                        SELECT journal_id, NULL, gen_random_uuid(), gen_random_uuid(), NULL, 'KRW', 10000, at, 0, 0
                        FROM x ORDER BY i, k
                        RETURNING journal_id)
                    INSERT INTO settlement_seller_allocation (journal_id, order_id, seller_id, amount_won,
                        allocation_ordinal, recorded_at)
                    SELECT journal_id, gen_random_uuid(), CASE WHEN k = 0 THEN ?::uuid ELSE gen_random_uuid() END,
                           10000, 0, at
                    FROM x ORDER BY i, k
                    """, count, SCATTER_FILLER, seller);
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
