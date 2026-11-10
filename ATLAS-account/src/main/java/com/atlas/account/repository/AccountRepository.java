package com.atlas.account.repository;

import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.RefreshTokenSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private static final RowMapper<RefreshTokenSession> REFRESH_ROW = AccountRepository::mapRefreshSession;
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createAccount(String email, String passwordHash) {
        return jdbc.queryForObject("""
                INSERT INTO user_account(email, password_hash) VALUES (?, ?) RETURNING account_id
                """, UUID.class, email, passwordHash);
    }

    public UUID createBuyer(UUID accountId) {
        UUID buyerId = jdbc.queryForObject("INSERT INTO buyer(buyer_id) VALUES (uuidv7()) RETURNING buyer_id",
                UUID.class);
        jdbc.update("INSERT INTO buyer_account(account_id, buyer_id) VALUES (?, ?)", accountId, buyerId);
        return buyerId;
    }

    public void linkSeller(UUID accountId, UUID sellerId) {
        jdbc.update("INSERT INTO seller_account(account_id, seller_id) VALUES (?, ?)", accountId, sellerId);
    }

    public Optional<AccountPrincipal> findActiveByEmail(String email) {
        return findAccount("email = ?", email);
    }

    public Optional<AccountPrincipal> findActiveById(UUID accountId) {
        return findAccount("account_id = ?", accountId);
    }

    public Optional<String> findPasswordHash(String email) {
        List<String> hashes = jdbc.query("""
                        SELECT password_hash FROM user_account
                        WHERE email = ? AND status = 'ACTIVE'
                        """, (row, number) -> row.getString(1), email);
        return hashes.stream().findFirst();
    }

    public Optional<String> findBuyerRef(UUID accountId) {
        List<String> buyerRefs = jdbc.query("""
                        SELECT buyer_id::text FROM buyer_account WHERE account_id = ?
                        """, (row, number) -> row.getString(1), accountId);
        return buyerRefs.stream().findFirst();
    }

    public boolean ownsBuyer(UUID accountId, String buyerRef) {
        Boolean found = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM buyer_account
                            WHERE account_id = ? AND buyer_id::text = ?
                        )
                        """, Boolean.class, accountId, buyerRef);
        return Boolean.TRUE.equals(found);
    }

    public boolean ownsSeller(UUID accountId, UUID sellerId) {
        Boolean found = jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM seller_account
                            WHERE account_id = ? AND seller_id = ?
                        )
                        """, Boolean.class, accountId, sellerId);
        return Boolean.TRUE.equals(found);
    }

    public List<UUID> findSellerIds(UUID accountId) {
        return jdbc.query("""
                        SELECT seller_id FROM seller_account WHERE account_id = ? ORDER BY seller_id
                        """, (row, number) -> row.getObject(1, UUID.class), accountId);
    }

    public void createRefreshFamily(UUID familyId, UUID accountId, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO refresh_token_family(family_id, account_id, created_at, expires_at)
                        VALUES (?, ?, ?, ?)
                        """, familyId, accountId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public void insertRefreshSession(UUID tokenId, UUID familyId, String tokenHash,
            Instant issuedAt, Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO account_refresh_token(token_id, family_id, token_hash, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, tokenId, familyId, tokenHash, Timestamp.from(expiresAt), Timestamp.from(issuedAt));
    }

    public Optional<RefreshTokenSession> findRefreshSessionForUpdate(String tokenHash) {
        List<RefreshTokenSession> sessions = jdbc.query("""
                        SELECT session.token_id, session.family_id, family.account_id,
                               family.expires_at AS family_expires_at, family.revoked_at AS family_revoked_at,
                               session.expires_at, session.consumed_at, session.revoked_at
                        FROM account_refresh_token session
                        JOIN refresh_token_family family ON family.family_id = session.family_id
                        WHERE session.token_hash = ?
                        FOR UPDATE OF session, family
                        """, REFRESH_ROW, tokenHash);
        return sessions.stream().findFirst();
    }

    public boolean consumeRefreshSession(UUID tokenId, String replacementHash, Instant consumedAt) {
        return jdbc.update("""
                        UPDATE account_refresh_token
                        SET consumed_at = ?, replaced_by_hash = ?
                        WHERE token_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                        """, Timestamp.from(consumedAt), replacementHash, tokenId) == 1;
    }

    public void revokeRefreshFamily(UUID familyId, Instant revokedAt) {
        jdbc.update("""
                        UPDATE refresh_token_family SET revoked_at = ?
                        WHERE family_id = ? AND revoked_at IS NULL
                        """, Timestamp.from(revokedAt), familyId);
        jdbc.update("""
                        UPDATE account_refresh_token SET revoked_at = ?
                        WHERE family_id = ? AND revoked_at IS NULL
                        """, Timestamp.from(revokedAt), familyId);
    }

    public void revokeRefreshToken(UUID accountId, String tokenHash, Instant revokedAt) {
        jdbc.update("""
                        UPDATE account_refresh_token session SET revoked_at = ?
                        FROM refresh_token_family family
                        WHERE session.family_id = family.family_id AND family.account_id = ?
                          AND session.token_hash = ? AND session.revoked_at IS NULL
                        """, Timestamp.from(revokedAt), accountId, tokenHash);
    }

    private Optional<AccountPrincipal> findAccount(String predicate, Object value) {
        String query = "SELECT account_id, email FROM user_account WHERE status = 'ACTIVE' AND " + predicate;
        List<AccountRow> matches = jdbc.query(query, (row, number) ->
                new AccountRow(row.getObject("account_id", UUID.class), row.getString("email")), value);
        if (matches.isEmpty()) return Optional.empty();
        AccountRow account = matches.getFirst();
        UUID buyerId = findBuyerId(account.accountId()).orElse(null);
        return Optional.of(new AccountPrincipal(account.accountId(), account.email(), buyerId,
                findSellerIds(account.accountId())));
    }

    private Optional<UUID> findBuyerId(UUID accountId) {
        List<UUID> buyerIds = jdbc.query("SELECT buyer_id FROM buyer_account WHERE account_id = ?",
                (row, number) -> row.getObject(1, UUID.class), accountId);
        return buyerIds.stream().findFirst();
    }

    private static RefreshTokenSession mapRefreshSession(ResultSet row, int number) throws SQLException {
        return new RefreshTokenSession(row.getObject("token_id", UUID.class),
                row.getObject("family_id", UUID.class), row.getObject("account_id", UUID.class),
                instant(row, "family_expires_at"), instant(row, "family_revoked_at"),
                instant(row, "expires_at"), instant(row, "consumed_at"), instant(row, "revoked_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record AccountRow(UUID accountId, String email) {
    }
}
