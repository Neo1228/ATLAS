package com.atlas.agent.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssistantSpendingCapRepository {
    private final JdbcTemplate jdbc;

    public AssistantSpendingCapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> find(String buyerRef) {
        return jdbc.queryForList("SELECT cap_won FROM assistant_spending_cap WHERE buyer_ref = ?", Long.class, buyerRef)
                .stream().findFirst();
    }

    public void upsert(String buyerRef, long capWon, Instant now) {
        jdbc.update("""
                INSERT INTO assistant_spending_cap (buyer_ref, cap_won, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (buyer_ref) DO UPDATE SET cap_won = EXCLUDED.cap_won, updated_at = EXCLUDED.updated_at
                """, buyerRef, capWon, Timestamp.from(now));
    }

    public void delete(String buyerRef) {
        jdbc.update("DELETE FROM assistant_spending_cap WHERE buyer_ref = ?", buyerRef);
    }
}
