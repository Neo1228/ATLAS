package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 런타임 역할을 소유 역할과 나눠도 앱이 끝까지 동작하고, 런타임 역할은 원장을 고칠 수 없음을 PostgreSQL로
 * 확인한다(EXP-111 H5, H6).
 *
 * <p>구성은 {@code scripts/db/runtime-role.sql} 그대로다. 마이그레이션 전에 한 번(역할과 기본 권한), 첫 기동
 * 뒤에 한 번(원장 회수) 실행한다. 데이터소스는 런타임 역할, Flyway는 {@code spring.flyway.url/user/password}로
 * 소유 계정이다. 데이터소스 URL의 {@code statement_timeout}(1.5초)이 3초짜리 마이그레이션(V99)을 죽이지
 * 않는 것으로 Flyway 연결이 URL 옵션을 물려받지 않음을 확인한다(EXP-109 R1의 해법).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementLedgerRoleSeparationIntegrationTest extends PayPalIntegrationTestSupport {
    private static final String RUNTIME_ROLE = "atlas_runtime";
    private static final String RUNTIME_PASSWORD = "runtime-local-only";
    private static final Path ROLE_SCRIPT = Path.of("..", "scripts", "db", "runtime-role.sql").toAbsolutePath().normalize();
    private static final AtomicBoolean ROLE_SCRIPT_APPLIED_AFTER_MIGRATION = new AtomicBoolean();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas")
            .withCopyFileToContainer(MountableFile.forHostPath(ROLE_SCRIPT), "/runtime-role.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        applyRoleScript();
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&options=-c%20statement_timeout%3D1500");
        registry.add("spring.datasource.username", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/rolecheck");
        paypalTestProperties(registry);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.settlement.fee-rate-bps", () -> "1000");
        registry.add("atlas.settlement.reserve-rate-bps", () -> "1000");
    }

    @Autowired private SettlementService settlement;
    @Autowired private SettlementPayoutService payouts;
    @Autowired private JdbcTemplate runtime;

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** 첫 기동(Flyway) 뒤에 같은 스크립트를 다시 실행해 원장 회수를 적용한다. 멱등이라 여러 번 실행해도 같다. */
    @BeforeEach
    void applyRoleScriptAfterMigration() throws Exception {
        if (ROLE_SCRIPT_APPLIED_AFTER_MIGRATION.compareAndSet(false, true)) {
            applyRoleScript();
        }
    }

    @Test
    void appPostsCaptureRefundAndPayoutAsTheRestrictedRuntimeRole() {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME_ROLE);
        UUID sellerId = UUID.randomUUID();
        owner().update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);

        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        SettlementPostingRequest.Refund refund = new SettlementPostingRequest.Refund(
                UUID.randomUUID(), UUID.randomUUID(), sellerId, 2_000);
        settlement.reserveRefund(refund);
        settlement.recordRefund(refund);
        var payout = payouts.request(sellerId, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"), "role-payout-" + sellerId);
        payouts.approve(payout.payoutId());
        payouts.beginDispatch(payout.payoutId());
        payouts.confirmProviderSuccess(payout.payoutId(), payout.amountWon() + 11, 11, 25);

        assertThat(runtime.queryForObject(
                "SELECT count(*) FROM settlement_journal WHERE seller_id = ?", Long.class, sellerId)).isEqualTo(3L);
        assertThat(runtime.queryForObject(
                "SELECT state FROM settlement_payout WHERE payout_id = ?", String.class, payout.payoutId()))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void runtimeRoleCannotMutateTheLedgerOrDisableItsGuards() throws Exception {
        // 이전에 잘못 부여된 권한도 스크립트를 다시 실행하면 회수된다(멱등 수렴).
        owner().execute("GRANT UPDATE, DELETE, TRUNCATE ON settlement_entry TO " + RUNTIME_ROLE);
        applyRoleScript();
        UUID sellerId = UUID.randomUUID();
        owner().update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));
        long entries = owner().queryForObject("SELECT count(*) FROM settlement_entry", Long.class);

        for (String statement : List.of(
                "UPDATE settlement_entry SET amount_won = amount_won + 1",
                "DELETE FROM settlement_entry",
                "UPDATE settlement_journal SET fee_amount_won = 0",
                "DELETE FROM settlement_journal",
                "UPDATE settlement_seller_allocation SET amount_won = amount_won",
                "DELETE FROM settlement_seller_allocation",
                "TRUNCATE settlement_entry",
                "ALTER TABLE settlement_entry DISABLE TRIGGER settlement_entry_append_only",
                "DROP TRIGGER settlement_entry_append_only ON settlement_entry",
                "SET session_replication_role = replica",
                "UPDATE flyway_schema_history SET success = false",
                "CREATE TABLE runtime_role_must_not_create (id int)")) {
            assertThatThrownBy(() -> runtime.execute(statement))
                    .as(statement)
                    .satisfies(error -> assertThat(sqlState(error)).as(statement).isEqualTo("42501"));
        }
        assertThat(owner().queryForObject("SELECT count(*) FROM settlement_entry", Long.class)).isEqualTo(entries);
    }

    @Test
    void flywayRunsAsTheOwnerOnItsOwnConnectionWhileTheRuntimeUrlKeepsItsTimeout() {
        assertThat(runtime.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("1500ms");
        // V99 는 pg_sleep(3) 한 문장이다. 앱 URL 의 1.5초 한도가 Flyway 연결에 적용됐다면 마이그레이션이 취소됐다.
        assertThat(owner().queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .contains("7", "99");
        assertThat(owner().queryForList("""
                SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'
                """, String.class)).containsExactly(POSTGRES.getUsername());
    }

    private static void applyRoleScript() throws IOException, InterruptedException {
        assertThat(ROLE_SCRIPT).exists();
        assertThat(Files.size(ROLE_SCRIPT)).isPositive();
        ExecResult result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
                "-v", "runtime_role=" + RUNTIME_ROLE, "-v", "runtime_password=" + RUNTIME_PASSWORD,
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", "/runtime-role.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
