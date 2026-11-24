package com.atlas.settlement.repository;

import com.atlas.settlement.model.PayoutOperatorActionType;
import com.atlas.settlement.service.PayoutOperatorActionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class JdbcPayoutOperatorActionStore implements PayoutOperatorActionStore {
    private final JdbcTemplate jdbc;

    public JdbcPayoutOperatorActionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional
    @Override
    public boolean record(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action, String actor,
            String reason, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO settlement_payout_operator_action (
                    payout_id, idempotency_key, action, actor, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (payout_id, idempotency_key) DO NOTHING
                """, payoutId, idempotencyKey, action.name(), actor, reason, Timestamp.from(now));
        if (inserted == 1) return true;
        List<AuditFacts> facts = jdbc.query("""
                SELECT action, actor, reason FROM settlement_payout_operator_action
                WHERE payout_id = ? AND idempotency_key = ?
                """, (row, number) -> auditFacts(row), payoutId, idempotencyKey);
        return facts.size() == 1 && facts.getFirst().equals(new AuditFacts(action, actor, reason));
    }

    @Transactional(readOnly = true)
    @Override
    public boolean matches(UUID payoutId, String idempotencyKey, PayoutOperatorActionType action, String actor,
            String reason) {
        List<AuditFacts> facts = jdbc.query("""
                SELECT action, actor, reason FROM settlement_payout_operator_action
                WHERE payout_id = ? AND idempotency_key = ?
                """, (row, number) -> auditFacts(row), payoutId, idempotencyKey);
        return facts.size() == 1 && facts.getFirst().equals(new AuditFacts(action, actor, reason));
    }

    private static AuditFacts auditFacts(ResultSet row) throws SQLException {
        return new AuditFacts(PayoutOperatorActionType.valueOf(row.getString("action")),
                row.getString("actor"), row.getString("reason"));
    }

    private record AuditFacts(PayoutOperatorActionType action, String actor, String reason) { }
}
