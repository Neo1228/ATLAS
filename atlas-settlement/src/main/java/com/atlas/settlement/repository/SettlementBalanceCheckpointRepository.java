package com.atlas.settlement.repository;

import com.atlas.settlement.persistence.projection.BalanceCheckpointPending;
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
 * checkout·환불·지급의 잠금은 그대로다. 잔액 읽기는 마감 행 + 커서 이후 항목(꼬리)을 더한다
 * ({@link SettlementJournalRepository#sellerBalance}).
 *
 * <p>커서 규칙(DB 역할과 무관): {@code entry_id}는 DB가 그 트랜잭션의 xid를 받은 뒤 읽은 시계로 만드는 UUIDv7 이다
 * (V10 {@code settlement_entry_next_id()}). 마감 한 번은 (자기 트랜잭션 시작 시각 − 안전 지연의 UUIDv7 하한, 자기 xid)를
 * pending 으로 남긴다. 그 하한 아래 id 를 가진 항목은 그 시각 전에 xid를 받은, 즉 pending xid 보다 작은 xid의
 * 트랜잭션만 쓸 수 있다. 다음 마감은 {@code pg_snapshot_xmin(pg_current_snapshot())}이 pending xid 를 넘었을 때(그
 * 트랜잭션들이 모두 커밋 또는 롤백으로 끝났을 때)만 커서를 pending 까지 옮긴다. 스냅숏의 xmin 은 클러스터의 모든 쓰기
 * 트랜잭션을 역할과 무관하게 센다. 안전 지연은 DB 서버 시계가 뒤로 움직이는 경우의 여유다.
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

    /**
     * 앞 마감이 남긴 pending 경계와, 그 xid 보다 먼저 xid를 받은 트랜잭션이 모두 끝났는지. 커서 잠금 뒤 별도 문장으로
     * 읽어 잠금 이후의 스냅숏으로 판단한다.
     */
    public Optional<BalanceCheckpointPending> pending() {
        return jdbc.query("""
                SELECT pending_entry_id,
                       pending_xid IS NOT NULL AND pg_snapshot_xmin(pg_current_snapshot()) > pending_xid AS settled
                FROM settlement_balance_checkpoint_cursor
                WHERE pending_entry_id IS NOT NULL
                """, resultSet -> resultSet.next()
                ? Optional.of(new BalanceCheckpointPending(resultSet.getObject("pending_entry_id", UUID.class),
                        resultSet.getBoolean("settled")))
                : Optional.empty());
    }

    /** 새 pending: 이 트랜잭션 시작 시각 − 안전 지연의 UUIDv7 하한과 이 트랜잭션의 xid. 시작 시각은 xid 배정보다 앞선다. */
    public void recordPending(Duration safetyLag) {
        Instant at = jdbc.queryForObject("SELECT now() - make_interval(secs => ?)", Timestamp.class,
                (double) safetyLag.toMillis() / 1000).toInstant();
        jdbc.update("UPDATE settlement_balance_checkpoint_cursor SET pending_entry_id = ?, "
                + "pending_xid = pg_current_xact_id()", uuidV7Floor(at));
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
