package com.atlas.settlement.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 판매자 잔액 마감(checkpoint)을 쓰기 경로 밖에서 앞으로 옮긴다(ADR-0104, EXP-115 Ae).
 *
 * <p>마감 행은 커서({@code entry_id}) 아래 항목의 판매자·계정별 합이다. 항목 기록은 이 표를 건드리지 않으므로 capture·
 * checkout·환불·지급의 쓰기 비용과 잠금은 그대로다. 잔액 읽기는 마감 행 + 커서 이후 항목(꼬리)을 더한다
 * ({@link SettlementJournalRepository#sellerBalance}).
 *
 * <p>커서 규칙: {@code entry_id}는 DB가 INSERT 시각으로 만드는 UUIDv7 이다. 커밋되지 않은 항목은 쓰기를 시작한(xid가
 * 있는) 트랜잭션에만 있고 그 항목의 시각은 그 트랜잭션 시작 이후다. 그래서 커서를 "쓰는 트랜잭션 중 가장 이른 시작"과
 * "지금 − 안전 지연" 중 이른 쪽보다 앞으로 옮기지 않으면, 커서 아래로 나중에 커밋되는 항목이 생기지 않는다. 다른 DB
 * 역할의 세션은 {@code pg_stat_activity}에서 시작 시각이 보이지 않을 수 있어 안전 지연이 그 몫을 막는다(원장 정정 중에는
 * 마감 worker를 끄는 것이 운영 절차다).
 */
@Repository
public class SettlementBalanceCheckpointRepository {
    private final JdbcTemplate jdbc;

    public SettlementBalanceCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 커서 행을 잠그고 값을 돌려준다. 다른 인스턴스가 마감 중이면 기다리지 않고 빈 값. */
    public Optional<UUID> lockCursor() {
        return jdbc.query("SELECT entry_id FROM settlement_balance_checkpoint_cursor FOR UPDATE SKIP LOCKED",
                resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getObject("entry_id", UUID.class)) : Optional.empty());
    }

    /** 커서를 이 시각보다 앞으로 옮기지 않는다: 쓰기를 시작한 다른 트랜잭션의 가장 이른 시작과 지금 − 안전 지연 중 이른 쪽. */
    public Instant writeHorizon(Duration safetyLag) {
        return jdbc.queryForObject("""
                SELECT least(now() - make_interval(secs => ?), COALESCE(min(xact_start), now()))
                FROM pg_stat_activity
                WHERE backend_xid IS NOT NULL AND pid <> pg_backend_pid()
                """, Timestamp.class, (double) safetyLag.toMillis() / 1000).toInstant();
    }

    /** [from, to) 안 항목이 maxEntries 개를 넘으면 그 경계의 entry_id, 아니면 to. 한 번의 마감이 읽는 양을 묶는다. */
    public UUID batchEnd(UUID from, UUID to, int maxEntries) {
        List<UUID> boundary = jdbc.query("""
                SELECT entry_id FROM settlement_entry
                WHERE entry_id >= ? AND entry_id < ?
                ORDER BY entry_id
                OFFSET ? LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getObject("entry_id", UUID.class), from, to, maxEntries);
        return boundary.isEmpty() ? to : boundary.get(0);
    }

    /** [from, to) 항목을 판매자별로 마감 행에 더한다. 판매자 순서로 행을 잡는다. 바뀐 판매자 수를 돌려준다. */
    public int fold(UUID from, UUID to) {
        return jdbc.update("""
                INSERT INTO settlement_seller_balance_checkpoint AS b (seller_id, payable_won, reserve_won, fee_won)
                SELECT seller_id,
                    COALESCE(SUM(CASE WHEN account_code = 'SELLER_PAYABLE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN account_code = 'SELLER_RESERVE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN account_code = 'PLATFORM_FEE'
                        THEN CASE WHEN entry_side = 'CREDIT' THEN amount_won ELSE -amount_won END ELSE 0 END), 0)
                FROM settlement_entry
                WHERE entry_id >= ? AND entry_id < ? AND seller_id IS NOT NULL
                  AND account_code IN ('SELLER_PAYABLE', 'SELLER_RESERVE', 'PLATFORM_FEE')
                GROUP BY seller_id
                ORDER BY seller_id
                ON CONFLICT (seller_id) DO UPDATE SET
                    payable_won = b.payable_won + EXCLUDED.payable_won,
                    reserve_won = b.reserve_won + EXCLUDED.reserve_won,
                    fee_won = b.fee_won + EXCLUDED.fee_won
                """, from, to);
    }

    public void advanceCursor(UUID to) {
        jdbc.update("UPDATE settlement_balance_checkpoint_cursor SET entry_id = ?, folded_at = now()", to);
    }

    /** 이 시각에 만들어질 수 있는 가장 작은 UUIDv7(밀리초 이하 비트와 난수 비트가 0). */
    public static UUID uuidV7Floor(Instant at) {
        long millis = at.toEpochMilli();
        return new UUID((millis << 16) | 0x7000L, 0x8000_0000_0000_0000L);
    }
}
