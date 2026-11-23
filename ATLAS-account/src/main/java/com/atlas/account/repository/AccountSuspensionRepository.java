package com.atlas.account.repository;

import com.atlas.account.persistence.projection.SuspensionAction;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 운영자 계정 정지 상태와 그 감사 기록을 다룹니다.
 *
 * <p>본인 폐쇄({@code AccountRepository#closeAccount})와 분리합니다. 폐쇄는 이용자
 * 의사에 따른 단방향 전이이고, 정지는 운영 판단이라 해제될 수 있습니다. 같은
 * {@code status} 컬럼을 쓰지만 근거와 책임이 달라 감사를 따로 남깁니다.
 */
@Repository
public class AccountSuspensionRepository {
    private final JdbcTemplate jdbc;

    public AccountSuspensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 대상 계정이 존재하는지 확인합니다. 없는 계정은 404 로 응답해야 한다. */
    public boolean accountExists(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_account WHERE account_id = ?)", Boolean.class, accountId));
    }

    /**
     * 이미 같은 키로 처리된 요청인지 확인합니다.
     *
     * <p>감사 기록에 unique 제약이 있으므로, 선조회로 중복 삽입 예외를 피하고
     * 그 사이 경쟁이 나면 삽입이 실패해 롤백됩니다. 즉 결과는 항상 한 건입니다.
     */
    public boolean actionRecorded(UUID accountId, String idempotencyKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM account_suspension_action
                    WHERE account_id = ? AND idempotency_key = ?)
                """, Boolean.class, accountId, idempotencyKey));
    }

    /**
     * 계정 상태를 바꾸고 감사 기록을 남깁니다.
     *
     * @param suspend {@code true} 면 DISABLED, {@code false} 면 ACTIVE 로 되돌립니다.
     * @return 상태가 실제로 바뀌었으면 {@code true}. 이미 그 상태였으면 {@code false}.
     */
    public boolean apply(UUID accountId, boolean suspend, String idempotencyKey, String actor,
            String reason, Instant now) {
        String target = suspend ? "DISABLED" : "ACTIVE";
        String previous = suspend ? "ACTIVE" : "DISABLED";
        int updated = jdbc.update("""
                UPDATE user_account SET status = ?, updated_at = ?
                WHERE account_id = ? AND status = ?
                """, target, Timestamp.from(now), accountId, previous);
        jdbc.update("""
                INSERT INTO account_suspension_action (
                    suspension_action_id, account_id, action, idempotency_key, actor, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), accountId, suspend ? "SUSPEND" : "RELEASE",
                idempotencyKey, actor, reason, Timestamp.from(now));
        return updated > 0;
    }

    /** 한 계정의 감사 기록을 최신순으로 돌려줍니다. 운영 확인용입니다. */
    public List<SuspensionAction> history(UUID accountId, int limit) {
        return jdbc.query("""
                SELECT action, idempotency_key, actor, reason, created_at
                FROM account_suspension_action
                WHERE account_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, row) -> new SuspensionAction(
                        rs.getString("action"),
                        rs.getString("idempotency_key"),
                        rs.getString("actor"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), accountId, limit);
    }
}
