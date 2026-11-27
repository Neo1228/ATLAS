package com.atlas.account.repository;

import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.persistence.projection.ClaimedAccountNotification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 인증 메일 outbox(V9). 행은 (계정, 목적)마다 하나이고 토큰·수신 주소를 담지 않는다.
 *
 * <p>청구는 {@code available_at}을 임대 만료 시각으로 밀고 {@code lease_owner}를 적는다. 완료·재예약은
 * 같은 {@code lease_owner}일 때만 반영되므로, 임대가 끝나 다른 worker가 다시 잡았거나 사용자가 다시 요청해
 * 행이 초기화된 뒤에는 늦게 끝난 worker의 기록이 무시된다.
 */
@Repository
public class AccountNotificationRepository {
    private final JdbcTemplate jdbc;

    public AccountNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자의 transaction에 전달 의도를 기록한다. 같은 목적의 기존 행은 처음 대기 상태로 되돌린다. */
    public void enqueue(UUID accountId, AccountActionTokenPurpose purpose, Instant requestedAt) {
        jdbc.update("""
                INSERT INTO account_notification_outbox(account_id, purpose, requested_at, available_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, purpose) DO UPDATE
                SET status = 'PENDING', attempts = 0, requested_at = EXCLUDED.requested_at,
                    available_at = EXCLUDED.available_at, lease_owner = NULL, last_failure = NULL
                """, accountId, purpose.name(), Timestamp.from(requestedAt), Timestamp.from(requestedAt));
    }

    /** 기한이 된 대기 행 하나에 임대를 건다. 다른 worker가 잡은 행은 건너뛴다. */
    public Optional<ClaimedAccountNotification> claimNext(UUID leaseOwner, Instant now, Instant leaseExpiresAt) {
        List<ClaimedAccountNotification> claimed = jdbc.query("""
                WITH next AS (
                    SELECT account_id, purpose FROM account_notification_outbox
                    WHERE status = 'PENDING' AND available_at <= ?
                    ORDER BY available_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE account_notification_outbox outbox
                SET lease_owner = ?, available_at = ?, attempts = outbox.attempts + 1
                FROM next, user_account account
                WHERE outbox.account_id = next.account_id AND outbox.purpose = next.purpose
                  AND account.account_id = outbox.account_id
                RETURNING outbox.account_id, outbox.purpose, outbox.lease_owner, outbox.attempts,
                          outbox.requested_at, account.email, account.status,
                          account.email_verified_at IS NOT NULL AS email_verified
                """, (row, number) -> new ClaimedAccountNotification(
                row.getObject("account_id", UUID.class),
                AccountActionTokenPurpose.valueOf(row.getString("purpose")),
                row.getObject("lease_owner", UUID.class), row.getInt("attempts"),
                row.getTimestamp("requested_at").toInstant(), row.getString("email"),
                "ACTIVE".equals(row.getString("status")), row.getBoolean("email_verified")),
                Timestamp.from(now), leaseOwner, Timestamp.from(leaseExpiresAt));
        return claimed.stream().findFirst();
    }

    /** 전송했거나 보낼 필요가 없어진 행을 지운다. */
    public boolean complete(UUID accountId, AccountActionTokenPurpose purpose, UUID leaseOwner) {
        return jdbc.update("""
                DELETE FROM account_notification_outbox
                WHERE account_id = ? AND purpose = ? AND lease_owner = ?
                """, accountId, purpose.name(), leaseOwner) == 1;
    }

    public boolean reschedule(UUID accountId, AccountActionTokenPurpose purpose, UUID leaseOwner,
            Instant availableAt, String failure) {
        return jdbc.update("""
                UPDATE account_notification_outbox
                SET available_at = ?, last_failure = ?, lease_owner = NULL
                WHERE account_id = ? AND purpose = ? AND lease_owner = ?
                """, Timestamp.from(availableAt), failure, accountId, purpose.name(), leaseOwner) == 1;
    }

    public boolean markFailed(UUID accountId, AccountActionTokenPurpose purpose, UUID leaseOwner, String failure) {
        return jdbc.update("""
                UPDATE account_notification_outbox
                SET status = 'FAILED', last_failure = ?, lease_owner = NULL
                WHERE account_id = ? AND purpose = ? AND lease_owner = ?
                """, failure, accountId, purpose.name(), leaseOwner) == 1;
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM account_notification_outbox WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    public Optional<Instant> oldestPendingRequestedAt() {
        Timestamp oldest = jdbc.queryForObject(
                "SELECT min(requested_at) FROM account_notification_outbox WHERE status = 'PENDING'",
                Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }
}
