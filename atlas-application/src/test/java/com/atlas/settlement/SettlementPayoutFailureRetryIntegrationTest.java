package com.atlas.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.dto.request.SettlementPostingRequest;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.atlas.settlement.model.SettlementPayoutState;
import com.atlas.settlement.service.SettlementPayoutService;
import com.atlas.settlement.service.SettlementService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 운영자가 실패(FAILED) 처리한 지급이 같은 기간의 재지급 요청을 막지 않는지 검증한다.
 *
 * <p>회귀 대상: FAILED 지급은 잔액을 예약하지 않는데도, 같은 (seller, 기간) 의 새 요청이
 * 기간 조회와 unique 제약에 걸려 FAILED 행을 그대로 돌려받았다. 잔액은 보이지만 그 기간으로는
 * 영원히 지급할 수 없었다. mock 없이 실제 PostgreSQL 과 실제 서비스 bean 으로 확인한다.
 *
 * <p>수수료율·보류율은 테스트 프로퍼티로 고정한다(각 10% = 1000 bps).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext
class SettlementPayoutFailureRetryIntegrationTest extends PayPalIntegrationTestSupport {
    private static final Instant PERIOD_START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-12-01T00:00:00Z");

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

    @BeforeEach
    void clearLedgerAndPayouts() {
        // 지급 row 가 누적되면 pendingAmount 가 잔액을 예약된 것으로 오판한다. 빈 원장에서 시작한다.
        jdbc.update("DELETE FROM settlement_entry");
        jdbc.update("DELETE FROM settlement_seller_allocation");
        jdbc.update("DELETE FROM settlement_journal");
        jdbc.update("DELETE FROM settlement_payout");
    }

    @Test
    void failedPayoutDoesNotBlockNewRequestForSamePeriod() {
        UUID sellerId = createSeller();
        // gross 10,000 -> fee 1,000 / reserve 1,000 / seller payable 8,000
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));

        PayoutResponse.SellerPayout first = payouts.request(sellerId, PERIOD_START, PERIOD_END, "retry-key-1");
        assertThat(first.state()).isEqualTo(SettlementPayoutState.REQUESTED);
        assertThat(first.amountWon()).isEqualTo(8_000L);

        PayoutResponse.SellerPayout failed = payouts.fail(first.payoutId(), "BANK_REJECTED");
        assertThat(failed.state()).isEqualTo(SettlementPayoutState.FAILED);
        // FAILED 는 더 이상 잔액을 예약하지 않는다. 돈은 다시 지급 가능하다.
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isZero();
        assertThat(payouts.summary(sellerId).payableAmountWon()).isEqualTo(8_000L);

        // 같은 key 재전송은 같은 요청의 재시도다. 실패 결과를 FAILED 상태 그대로 돌려주고 새 지급을 만들지 않는다.
        PayoutResponse.SellerPayout replay = payouts.request(sellerId, PERIOD_START, PERIOD_END, "retry-key-1");
        assertThat(replay.payoutId()).isEqualTo(first.payoutId());
        assertThat(replay.state()).isEqualTo(SettlementPayoutState.FAILED);
        assertThat(payoutCount(sellerId)).isEqualTo(1);

        // 새 key 로 같은 기간을 다시 요청하면 새 지급이 생긴다(이전 동작: FAILED 행을 돌려받았다).
        PayoutResponse.SellerPayout retried = payouts.request(sellerId, PERIOD_START, PERIOD_END, "retry-key-2");
        assertThat(retried.payoutId()).isNotEqualTo(first.payoutId());
        assertThat(retried.state()).isEqualTo(SettlementPayoutState.REQUESTED);
        assertThat(retried.periodStart()).isEqualTo(PERIOD_START);
        assertThat(retried.periodEnd()).isEqualTo(PERIOD_END);
        assertThat(retried.amountWon()).isEqualTo(8_000L);
        assertThat(payouts.summary(sellerId).pendingPayoutAmountWon()).isEqualTo(8_000L);
        // 실패한 지급은 이력으로 남는다.
        assertThat(payouts.find(first.payoutId()).state()).isEqualTo(SettlementPayoutState.FAILED);
        assertThat(payoutCount(sellerId)).isEqualTo(2);

        // 새 지급은 정상 수명주기를 끝까지 탈 수 있고, 확정되면 지급 가능 잔액이 0 이 된다.
        payouts.approve(retried.payoutId());
        payouts.markSent(retried.payoutId());
        PayoutResponse.SellerPayout confirmed = payouts.confirm(retried.payoutId());
        assertThat(confirmed.state()).isEqualTo(SettlementPayoutState.CONFIRMED);
        assertThat(payouts.summary(sellerId).payableAmountWon()).isZero();
        assertThat(payouts.summary(sellerId).confirmedPayoutAmountWon()).isEqualTo(8_000L);
    }

    @Test
    void twoNonFailedPayoutsForSamePeriodStillCannotCoexist() {
        UUID sellerId = createSeller();
        settlement.recordCapture(new SettlementPostingRequest.Capture(UUID.randomUUID(), UUID.randomUUID(), sellerId, 10_000));

        PayoutResponse.SellerPayout first = payouts.request(sellerId, PERIOD_START, PERIOD_END, "dup-key-1");
        payouts.fail(first.payoutId(), "BANK_REJECTED");
        PayoutResponse.SellerPayout active = payouts.request(sellerId, PERIOD_START, PERIOD_END, "dup-key-2");
        assertThat(active.state()).isEqualTo(SettlementPayoutState.REQUESTED);

        // 서비스 경로: 진행 중 지급이 있으면 새 key 라도 그 지급을 돌려준다(중복 지급 금지).
        PayoutResponse.SellerPayout duplicate = payouts.request(sellerId, PERIOD_START, PERIOD_END, "dup-key-3");
        assertThat(duplicate.payoutId()).isEqualTo(active.payoutId());
        assertThat(payoutCount(sellerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ? AND state <> 'FAILED'",
                Integer.class, sellerId)).isEqualTo(1);

        // DB 경로: 서비스를 우회해도 partial unique index 가 두 번째 비-FAILED 지급을 거부한다.
        assertThatThrownBy(() -> insertPayoutRow(sellerId, "APPROVED", "dup-key-direct"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 반면 FAILED 행은 같은 기간에 여러 개 있어도 된다.
        insertPayoutRow(sellerId, "FAILED", "dup-key-failed-direct");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ? AND state = 'FAILED'",
                Integer.class, sellerId)).isEqualTo(2);
    }

    private void insertPayoutRow(UUID sellerId, String state, String idempotencyKey) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO settlement_payout
                    (seller_id, period_start, period_end, amount_won, state, idempotency_key,
                     requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, sellerId, Timestamp.from(PERIOD_START), Timestamp.from(PERIOD_END), 1_000L,
                state, idempotencyKey, now, now);
    }

    private int payoutCount(UUID sellerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_payout WHERE seller_id = ?", Integer.class, sellerId);
    }

    private UUID createSeller() {
        UUID sellerId = UUID.randomUUID();
        jdbc.update("INSERT INTO seller(seller_id) VALUES (?)", sellerId);
        return sellerId;
    }
}
