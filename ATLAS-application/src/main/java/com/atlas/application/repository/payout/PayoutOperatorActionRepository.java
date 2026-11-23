package com.atlas.application.repository.payout;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable operator audit for decisions that create or release payout reservations. */
@Repository
public class PayoutOperatorActionRepository {
    private final JdbcTemplate jdbc;

    public PayoutOperatorActionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional
    public boolean record(UUID payoutId, String idempotencyKey, String action, String actor,
            String reason, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO settlement_payout_operator_action (
                    payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (payout_id, idempotency_key) DO NOTHING
                """, payoutId, idempotencyKey, action, actor, reason, Timestamp.from(now));
        if (inserted == 1) return true;
        List<AuditFacts> facts = jdbc.query("""
                SELECT action, actor, reason FROM settlement_payout_operator_action
                WHERE payout_id = ? AND idempotency_key = ?
                """, (row, number) -> new AuditFacts(
                row.getString("action"), row.getString("actor"), row.getString("reason")), payoutId, idempotencyKey);
        return facts.size() == 1 && facts.getFirst().equals(new AuditFacts(action, actor, reason));
    }

    @Transactional(readOnly = true)
    public boolean matches(UUID payoutId, String idempotencyKey, String action, String actor, String reason) {
        List<AuditFacts> facts = jdbc.query("""
                SELECT action, actor, reason FROM settlement_payout_operator_action
                WHERE payout_id = ? AND idempotency_key = ?
                """, (row, number) -> new AuditFacts(
                row.getString("action"), row.getString("actor"), row.getString("reason")), payoutId, idempotencyKey);
        return facts.size() == 1 && facts.getFirst().equals(new AuditFacts(action, actor, reason));
    }

    private record AuditFacts(String action, String actor, String reason) { }
}
