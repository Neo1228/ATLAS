package com.atlas.application.repository.payout;

import com.atlas.application.service.payout.SellerPayoutProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SellerPayoutProfileRepository {
    private final JdbcTemplate jdbc;

    public SellerPayoutProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SellerPayoutProfile> find(UUID sellerId) {
        return jdbc.query("""
                SELECT seller_id, recipient_account_id, country_code, payout_currency, status,
                       profile_version, updated_at, verified_at, verified_by
                FROM seller_payout_profile WHERE seller_id = ?
                """, (row, number) -> map(row), sellerId).stream().findFirst();
    }

    public SellerPayoutProfile submit(UUID sellerId, UUID accountId, String countryCode, Instant now) {
        List<SellerPayoutProfile> rows = jdbc.query("""
                INSERT INTO seller_payout_profile (
                    seller_id, recipient_account_id, country_code, payout_currency, status,
                    profile_version, created_at, updated_at)
                VALUES (?, ?, ?, 'USD', 'PENDING_REVIEW', 1, ?, ?)
                ON CONFLICT (seller_id) DO UPDATE SET
                    recipient_account_id = EXCLUDED.recipient_account_id,
                    country_code = EXCLUDED.country_code,
                    payout_currency = 'USD',
                    status = 'PENDING_REVIEW',
                    profile_version = seller_payout_profile.profile_version + 1,
                    verified_at = NULL,
                    verified_by = NULL,
                    updated_at = EXCLUDED.updated_at
                WHERE seller_payout_profile.recipient_account_id IS DISTINCT FROM EXCLUDED.recipient_account_id
                   OR seller_payout_profile.country_code IS DISTINCT FROM EXCLUDED.country_code
                RETURNING seller_id, recipient_account_id, country_code, payout_currency, status,
                          profile_version, updated_at, verified_at, verified_by
                """, (row, number) -> map(row), sellerId, accountId, countryCode,
                Timestamp.from(now), Timestamp.from(now));
        return rows.isEmpty() ? find(sellerId).orElseThrow(
                () -> new IllegalStateException("Payout profile disappeared after submit")) : rows.getFirst();
    }

    public Optional<SellerPayoutProfile> verify(UUID sellerId, int expectedVersion, String actor, Instant now) {
        return jdbc.query("""
                UPDATE seller_payout_profile
                SET status = 'ACTIVE', profile_version = profile_version + 1,
                    verified_at = ?, verified_by = ?, updated_at = ?
                WHERE seller_id = ? AND status = 'PENDING_REVIEW' AND profile_version = ?
                RETURNING seller_id, recipient_account_id, country_code, payout_currency, status,
                          profile_version, updated_at, verified_at, verified_by
                """, (row, number) -> map(row), Timestamp.from(now), actor, Timestamp.from(now),
                sellerId, expectedVersion).stream().findFirst();
    }

    public Optional<SellerPayoutProfile> suspend(UUID sellerId, int expectedVersion, Instant now) {
        return jdbc.query("""
                UPDATE seller_payout_profile
                SET status = 'SUSPENDED', profile_version = profile_version + 1, updated_at = ?
                WHERE seller_id = ? AND status = 'ACTIVE' AND profile_version = ?
                RETURNING seller_id, recipient_account_id, country_code, payout_currency, status,
                          profile_version, updated_at, verified_at, verified_by
                """, (row, number) -> map(row), Timestamp.from(now), sellerId, expectedVersion)
                .stream().findFirst();
    }

    public Optional<String> findRequestHash(UUID sellerId, String idempotencyKey) {
        return jdbc.query("""
                SELECT request_facts_sha256 FROM seller_payout_profile_action
                WHERE seller_id = ? AND idempotency_key = ?
                """, (row, number) -> row.getString(1), sellerId, idempotencyKey).stream().findFirst();
    }

    public boolean recordAction(UUID sellerId, int profileVersion, String action, String idempotencyKey,
            String requestHash, String actor, String reason, Instant now) {
        return jdbc.update("""
                INSERT INTO seller_payout_profile_action (
                    seller_id, profile_version, action, idempotency_key, request_facts_sha256,
                    actor, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (seller_id, idempotency_key) DO NOTHING
                """, sellerId, profileVersion, action, idempotencyKey, requestHash,
                actor, reason, Timestamp.from(now)) == 1;
    }

    private static SellerPayoutProfile map(ResultSet row) throws SQLException {
        Timestamp verifiedAt = row.getTimestamp("verified_at");
        return new SellerPayoutProfile(
                row.getObject("seller_id", UUID.class),
                row.getObject("recipient_account_id", UUID.class),
                row.getString("country_code"), row.getString("payout_currency"),
                SellerPayoutProfile.State.valueOf(row.getString("status")),
                row.getInt("profile_version"), row.getTimestamp("updated_at").toInstant(),
                verifiedAt == null ? null : verifiedAt.toInstant(), row.getString("verified_by"));
    }
}
