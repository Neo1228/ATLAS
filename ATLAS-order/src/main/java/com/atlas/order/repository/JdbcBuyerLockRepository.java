package com.atlas.order.repository;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBuyerLockRepository {
    private final JdbcTemplate jdbc;

    public JdbcBuyerLockRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public void lockBuyer(String buyerKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> { }, buyerKey);
    }
}
