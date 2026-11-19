package com.atlas.account.repository;

import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.AccountActionToken;
import com.atlas.account.domain.AccountMfaSecret;
import com.atlas.account.domain.RefreshTokenSession;
import com.atlas.account.domain.ActiveRefreshFamily;
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

    public Optional<UUID> createAccount(String email, String passwordHash) {
        List<UUID> accountIds = jdbc.query("""
                INSERT INTO user_account(email, password_hash) VALUES (?, ?)
                ON CONFLICT ((lower(email))) DO NOTHING
                RETURNING account_id
                """, (row, number) -> row.getObject(1, UUID.class), email, passwordHash);
        return accountIds.stream().findFirst();
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

    public Optional<String> lockPasswordHashForLogin(String email) {
        List<String> hashes = jdbc.query("""
                        SELECT password_hash FROM user_account
                        WHERE email = ? AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                        FOR UPDATE
                        """, (row, number) -> row.getString(1), email);
        return hashes.stream().findFirst();
    }

    public Optional<UUID> findVerifiedAccountIdByEmail(String email) {
        List<UUID> accountIds = jdbc.query("""
                SELECT account_id FROM user_account
                WHERE email = ? AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                """, (row, number) -> row.getObject(1, UUID.class), email);
        return accountIds.stream().findFirst();
    }

    public Optional<UUID> findUnverifiedAccountIdByEmail(String email) {
        List<UUID> accountIds = jdbc.query("""
                SELECT account_id FROM user_account
                WHERE email = ? AND status = 'ACTIVE' AND email_verified_at IS NULL
                """, (row, number) -> row.getObject(1, UUID.class), email);
        return accountIds.stream().findFirst();
    }

    public void replaceActionToken(UUID accountId, String purpose, String tokenHash,
            Instant issuedAt, Instant expiresAt) {
        jdbc.queryForObject("SELECT account_id FROM user_account WHERE account_id = ? FOR UPDATE",
                UUID.class, accountId);
        jdbc.update("""
                UPDATE account_action_token SET consumed_at = ?
                WHERE account_id = ? AND purpose = ? AND consumed_at IS NULL
                """, Timestamp.from(issuedAt), accountId, purpose);
        jdbc.update("""
                INSERT INTO account_action_token(account_id, purpose, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, accountId, purpose, tokenHash, Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    public Optional<AccountActionToken> findActionTokenForUpdate(String tokenHash, String purpose) {
        List<AccountActionToken> tokens = jdbc.query("""
                SELECT token_id, account_id, purpose, expires_at, consumed_at
                FROM account_action_token
                WHERE token_hash = ? AND purpose = ?
                FOR UPDATE
                """, (row, number) -> new AccountActionToken(
                row.getObject("token_id", UUID.class), row.getObject("account_id", UUID.class),
                row.getString("purpose"), row.getTimestamp("expires_at").toInstant(),
                instant(row, "consumed_at")), tokenHash, purpose);
        return tokens.stream().findFirst();
    }

    public boolean consumeActionToken(UUID tokenId, Instant consumedAt) {
        return jdbc.update("""
                UPDATE account_action_token SET consumed_at = ?
                WHERE token_id = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(consumedAt), tokenId, Timestamp.from(consumedAt)) == 1;
    }

    public void verifyEmail(UUID accountId, Instant verifiedAt) {
        jdbc.update("UPDATE user_account SET email_verified_at = ?, updated_at = ? WHERE account_id = ?",
                Timestamp.from(verifiedAt), Timestamp.from(verifiedAt), accountId);
    }

    public void updatePassword(UUID accountId, String passwordHash, Instant updatedAt) {
        jdbc.update("UPDATE user_account SET password_hash = ?, updated_at = ? WHERE account_id = ?",
                passwordHash, Timestamp.from(updatedAt), accountId);
    }

    /**
     * 계정을 폐쇄 상태로 바꿉니다.
     *
     * <p>{@code status = 'DISABLED'} 로 두면 {@code isRefreshFamilyActive} 와 모든 로그인
     * 쿼리가 이미 {@code status = 'ACTIVE'} 를 요구하므로, 이후 발급·갱신·검증이 모두
     * 거부됩니다. 행을 지우지 않는 이유는 주문·결제 이력의 참조 무결성을 유지하기
     * 위해서입니다.
     *
     * @return 이번 호출로 상태가 바뀌었으면 {@code true}. 이미 폐쇄였으면 {@code false}.
     */
    public boolean closeAccount(UUID accountId, Instant closedAt) {
        return jdbc.update("""
                UPDATE user_account SET status = 'DISABLED', updated_at = ?
                WHERE account_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(closedAt), accountId) > 0;
    }

    /** 폐쇄 여부와 무관하게 계정이 존재하는지 확인합니다. */
    public boolean accountExists(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_account WHERE account_id = ?)", Boolean.class, accountId));
    }

    /** 비밀번호 해시를 상태 조건 없이 읽습니다(폐쇄 판정용). */
    public Optional<String> findPasswordHashForClosure(UUID accountId) {
        return jdbc.query("SELECT password_hash FROM user_account WHERE account_id = ?",
                (rs, row) -> rs.getString("password_hash"), accountId).stream().findFirst();
    }

    public void revokeAllRefreshFamilies(UUID accountId, Instant revokedAt) {
        jdbc.update("""
                UPDATE refresh_token_family SET revoked_at = ?
                WHERE account_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), accountId);
        jdbc.update("""
                UPDATE account_refresh_token token SET revoked_at = ?
                FROM refresh_token_family family
                WHERE token.family_id = family.family_id AND family.account_id = ?
                  AND token.revoked_at IS NULL
                """, Timestamp.from(revokedAt), accountId);
    }

    public void revokeMfaChallenges(UUID accountId, Instant revokedAt) {
        jdbc.update("""
                UPDATE account_mfa_challenge SET consumed_at = ?
                WHERE account_id = ? AND consumed_at IS NULL
                """, Timestamp.from(revokedAt), accountId);
    }

    public Optional<AccountMfaSecret> findMfaSecretForUpdate(UUID accountId) {
        List<AccountMfaSecret> secrets = jdbc.query("""
                SELECT encrypted_secret, enabled_at FROM account_mfa_secret
                WHERE account_id = ? FOR UPDATE
                """, (row, number) -> new AccountMfaSecret(row.getString("encrypted_secret"),
                instant(row, "enabled_at")), accountId);
        return secrets.stream().findFirst();
    }

    public boolean mfaEnabled(UUID accountId) {
        Boolean enabled = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM account_mfa_secret WHERE account_id = ? AND enabled_at IS NOT NULL)
                """, Boolean.class, accountId);
        return Boolean.TRUE.equals(enabled);
    }

    public boolean savePendingMfaSecret(UUID accountId, String encryptedSecret, Instant createdAt) {
        return jdbc.update("""
                INSERT INTO account_mfa_secret(account_id, encrypted_secret, created_at)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET encrypted_secret = EXCLUDED.encrypted_secret,
                    created_at = EXCLUDED.created_at, last_accepted_step = NULL
                WHERE account_mfa_secret.enabled_at IS NULL
                """, accountId, encryptedSecret, Timestamp.from(createdAt)) == 1;
    }

    public boolean markMfaStepUsed(UUID accountId, long step) {
        return jdbc.update("""
                UPDATE account_mfa_secret SET last_accepted_step = ?
                WHERE account_id = ? AND (last_accepted_step IS NULL OR last_accepted_step < ?)
                """, step, accountId, step) == 1;
    }

    public boolean enableMfa(UUID accountId, Instant enabledAt) {
        return jdbc.update("""
                UPDATE account_mfa_secret SET enabled_at = ?
                WHERE account_id = ? AND enabled_at IS NULL AND last_accepted_step IS NOT NULL
                """, Timestamp.from(enabledAt), accountId) == 1;
    }

    public void replaceMfaRecoveryCodes(UUID accountId, List<String> codeHashes, Instant createdAt) {
        jdbc.update("DELETE FROM account_mfa_recovery_code WHERE account_id = ?", accountId);
        jdbc.batchUpdate("""
                INSERT INTO account_mfa_recovery_code(account_id, code_hash, created_at)
                VALUES (?, ?, ?)
                """, codeHashes, codeHashes.size(), (statement, hash) -> {
            statement.setObject(1, accountId);
            statement.setString(2, hash);
            statement.setTimestamp(3, Timestamp.from(createdAt));
        });
    }

    public boolean consumeMfaRecoveryCode(UUID accountId, String codeHash, Instant consumedAt) {
        return jdbc.update("""
                UPDATE account_mfa_recovery_code SET consumed_at = ?
                WHERE account_id = ? AND code_hash = ? AND consumed_at IS NULL
                """, Timestamp.from(consumedAt), accountId, codeHash) == 1;
    }

    public void disableMfa(UUID accountId) {
        jdbc.update("DELETE FROM account_mfa_secret WHERE account_id = ?", accountId);
    }

    public void createMfaChallenge(String challengeHash, UUID accountId, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO account_mfa_challenge(challenge_hash, account_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, challengeHash, accountId, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public Optional<UUID> findMfaChallengeForUpdate(String challengeHash, Instant now) {
        List<UUID> accountIds = jdbc.query("""
                SELECT account_id FROM account_mfa_challenge
                WHERE challenge_hash = ? AND consumed_at IS NULL AND expires_at > ? FOR UPDATE
                """, (row, number) -> row.getObject(1, UUID.class), challengeHash, Timestamp.from(now));
        return accountIds.stream().findFirst();
    }

    public boolean consumeMfaChallenge(String challengeHash, Instant consumedAt) {
        return jdbc.update("""
                UPDATE account_mfa_challenge SET consumed_at = ?
                WHERE challenge_hash = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(consumedAt), challengeHash, Timestamp.from(consumedAt)) == 1;
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

    public boolean isRefreshFamilyActive(UUID accountId, UUID familyId, Instant asOf) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM refresh_token_family family
                    JOIN user_account account ON account.account_id = family.account_id
                    WHERE family.family_id = ?
                      AND family.account_id = ?
                      AND family.revoked_at IS NULL
                      AND family.expires_at > ?
                      AND account.status = 'ACTIVE'
                      AND account.email_verified_at IS NOT NULL
                )
                """, Boolean.class, familyId, accountId, Timestamp.from(asOf)));
    }

    public List<ActiveRefreshFamily> findActiveRefreshFamilies(UUID accountId, Instant asOf,
            int limit, int offset) {
        return jdbc.query("""
                SELECT family.family_id, family.created_at, family.expires_at
                FROM refresh_token_family family
                JOIN user_account account ON account.account_id = family.account_id
                WHERE family.account_id = ? AND family.revoked_at IS NULL AND family.expires_at > ?
                  AND account.status = 'ACTIVE' AND account.email_verified_at IS NOT NULL
                ORDER BY family.created_at DESC, family.family_id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new ActiveRefreshFamily(
                rs.getObject("family_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()), accountId, Timestamp.from(asOf), limit, offset);
    }

    public boolean revokeRefreshFamily(UUID accountId, UUID familyId, Instant revokedAt) {
        int updated = jdbc.update("""
                UPDATE refresh_token_family
                SET revoked_at = ?
                WHERE family_id = ? AND account_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), familyId, accountId);
        if (updated == 0) return false;
        jdbc.update("""
                UPDATE account_refresh_token
                SET revoked_at = ?
                WHERE family_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), familyId);
        return true;
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
        String query = "SELECT account_id, email FROM user_account WHERE status = 'ACTIVE' "
                + "AND email_verified_at IS NOT NULL AND " + predicate;
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