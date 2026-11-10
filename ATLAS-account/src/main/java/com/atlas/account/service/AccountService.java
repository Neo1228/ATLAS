package com.atlas.account.service;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountException.Code;
import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.RefreshTokenSession;
import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AccountService {
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final Duration REFRESH_FAMILY_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AccessTokenService accessTokens;
    private final Clock clock;
    private final String dummyLoginPasswordHash;

    public AccountService(AccountRepository accounts, PasswordEncoder passwords,
            AccessTokenService accessTokens, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.clock = clock;
        this.dummyLoginPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AccountTokenResponse registerBuyer(RegisterBuyerRequest request) {
        if (request == null || request.password() == null || request.password().length() < 12
                || request.password().length() > 128) {
            throw invalidRequest();
        }
        String email = normalizeEmail(request.email());
        try {
            UUID accountId = accounts.createAccount(email, passwords.encode(request.password()));
            accounts.createBuyer(accountId);
            AccountPrincipal account = accounts.findActiveById(accountId).orElseThrow(AccountService::invalidCredentials);
            return issueTokens(account);
        } catch (DataIntegrityViolationException exception) {
            throw new AccountException(Code.EMAIL_ALREADY_USED, "An account already exists for this email");
        }
    }

    @Transactional
    public AccountTokenResponse login(LoginRequest request) {
        if (request == null || request.password() == null || request.password().length() > 128) {
            throw invalidCredentials();
        }
        String email = normalizeEmail(request.email());
        Optional<AccountPrincipal> account = accounts.findActiveByEmail(email);
        Optional<String> storedPasswordHash = accounts.findPasswordHash(email);
        String passwordHash = storedPasswordHash.orElse(dummyLoginPasswordHash);
        boolean passwordMatches = passwords.matches(request.password(), passwordHash);
        if (account.isEmpty() || storedPasswordHash.isEmpty() || !passwordMatches
                || account.get().buyerId() == null) {
            throw invalidCredentials();
        }
        return issueTokens(account.get());
    }

    @Transactional(noRollbackFor = AccountException.class)
    public AccountTokenResponse refresh(RefreshTokenRequest request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())) throw invalidRefreshToken();
        Instant now = clock.instant();
        RefreshTokenSession previous = accounts.findRefreshSessionForUpdate(hashRefreshToken(request.refreshToken()))
                .orElseThrow(AccountService::invalidRefreshToken);
        if (previous.consumedAt() != null) {
            accounts.revokeRefreshFamily(previous.familyId(), now);
            throw new AccountException(Code.REFRESH_TOKEN_REUSED, "Refresh token reuse detected");
        }
        if (previous.revokedAt() != null || previous.familyRevokedAt() != null
                || !now.isBefore(previous.expiresAt()) || !now.isBefore(previous.familyExpiresAt())) {
            throw invalidRefreshToken();
        }
        AccountPrincipal account = accounts.findActiveById(previous.accountId()).orElse(null);
        if (account == null || account.buyerId() == null) {
            accounts.revokeRefreshFamily(previous.familyId(), now);
            throw new AccountException(Code.ACCOUNT_DISABLED, "Account is unavailable");
        }

        String nextToken = newRefreshToken();
        String nextHash = hashRefreshToken(nextToken);
        if (!accounts.consumeRefreshSession(previous.tokenId(), nextHash, now)) {
            accounts.revokeRefreshFamily(previous.familyId(), now);
            throw new AccountException(Code.REFRESH_TOKEN_REUSED, "Refresh token reuse detected");
        }
        accounts.insertRefreshSession(UUID.randomUUID(), previous.familyId(), nextHash, now,
                minimum(now.plus(REFRESH_TOKEN_LIFETIME), previous.familyExpiresAt()));
        return tokenResponse(account, nextToken);
    }

    @Transactional(noRollbackFor = AccountException.class)
    public void logout(UUID accountId, String refreshToken) {
        if (accountId == null || !StringUtils.hasText(refreshToken)) throw invalidRequest();
        RefreshTokenSession session = accounts.findRefreshSessionForUpdate(hashRefreshToken(refreshToken))
                .orElseThrow(AccountService::invalidRefreshToken);
        if (!accountId.equals(session.accountId())) throw invalidRefreshToken();
        accounts.revokeRefreshFamily(session.familyId(), clock.instant());
    }

    @Transactional
    public void linkSeller(UUID accountId, UUID sellerId) {
        if (accountId == null || sellerId == null || accounts.findActiveById(accountId).isEmpty()) {
            throw new AccountException(Code.ACCOUNT_DISABLED, "Account is unavailable");
        }
        try {
            accounts.linkSeller(accountId, sellerId);
        } catch (DataIntegrityViolationException exception) {
            throw new AccountException(Code.RESOURCE_NOT_OWNED, "Seller profile is already linked");
        }
    }

    @Transactional(readOnly = true)
    public boolean ownsBuyer(UUID accountId, String buyerRef) {
        return accountId != null && StringUtils.hasText(buyerRef) && accounts.ownsBuyer(accountId, buyerRef);
    }

    @Transactional(readOnly = true)
    public boolean ownsSeller(UUID accountId, UUID sellerId) {
        return accountId != null && sellerId != null && accounts.ownsSeller(accountId, sellerId);
    }

    @Transactional(readOnly = true)
    public String buyerRef(UUID accountId) {
        return accounts.findBuyerRef(accountId).orElseThrow(() ->
                new AccountException(Code.RESOURCE_NOT_OWNED, "Buyer identity is unavailable"));
    }

    private AccountTokenResponse issueTokens(AccountPrincipal account) {
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiresAt = now.plus(REFRESH_FAMILY_LIFETIME);
        String refreshToken = newRefreshToken();
        accounts.createRefreshFamily(familyId, account.accountId(), now, familyExpiresAt);
        accounts.insertRefreshSession(UUID.randomUUID(), familyId, hashRefreshToken(refreshToken), now,
                minimum(now.plus(REFRESH_TOKEN_LIFETIME), familyExpiresAt));
        return tokenResponse(account, refreshToken);
    }

    private AccountTokenResponse tokenResponse(AccountPrincipal account, String refreshToken) {
        return new AccountTokenResponse(accessTokens.issue(account.accountId()), refreshToken, "Bearer",
                AccessTokenService.ACCESS_TOKEN_LIFETIME_SECONDS, account.accountId(), account.buyerRef(),
                account.sellerIds());
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static String normalizeEmail(String email) {
        if (!StringUtils.hasText(email) || email.length() > 320 || !email.contains("@")) throw invalidRequest();
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String newRefreshToken() {
        byte[] value = new byte[48];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeEmailAndCheck(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static AccountException invalidCredentials() {
        return new AccountException(Code.INVALID_CREDENTIALS, "Email or password is invalid");
    }

    private static AccountException invalidRefreshToken() {
        return new AccountException(Code.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired");
    }

    private static AccountException invalidRequest() {
        return new AccountException(Code.INVALID_REQUEST, "Authentication request is invalid");
    }
}
