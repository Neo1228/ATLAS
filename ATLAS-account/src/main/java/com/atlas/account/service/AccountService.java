package com.atlas.account.service;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountException.Code;
import com.atlas.account.domain.AccountActionToken;
import com.atlas.account.domain.AccountMfaSecret;
import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.RefreshTokenSession;
import com.atlas.account.domain.ActiveRefreshFamily;
import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.MfaChallengeRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.dto.response.AccountActionResponse;
import com.atlas.account.dto.response.AccountLoginResponse;
import com.atlas.account.dto.response.MfaEnrollmentResponse;
import com.atlas.account.dto.response.MfaRecoveryCodesResponse;
import com.atlas.account.dto.response.AccountSessionResponse;
import com.atlas.account.dto.response.AccountSessionPage;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.repository.AccountSuspensionRepository;
import com.atlas.account.dto.response.AccountSuspensionActionResponse;
import com.bastiaanjansen.otp.SecretGenerator;
import com.bastiaanjansen.otp.TOTPGenerator;
import java.nio.charset.StandardCharsets;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AccountService {
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final Duration REFRESH_FAMILY_LIFETIME = Duration.ofDays(30);
    private static final Duration EMAIL_VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_LIFETIME = Duration.ofMinutes(30);
    private static final Duration MFA_CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AccessTokenService accessTokens;
    private final AccountEmailSender emailSender;
    private final AccountMfaSecretProtector mfaSecrets;
    private final Clock clock;
    private final AccountSuspensionRepository suspensions;
    private final String dummyLoginPasswordHash;

    public AccountService(AccountRepository accounts, PasswordEncoder passwords,
            AccessTokenService accessTokens, AccountEmailSender emailSender,
            AccountMfaSecretProtector mfaSecrets, Clock clock,
            AccountSuspensionRepository suspensions) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.emailSender = emailSender;
        this.mfaSecrets = mfaSecrets;
        this.clock = clock;
        this.suspensions = suspensions;
        this.dummyLoginPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AccountActionResponse registerBuyer(RegisterBuyerRequest request) {
        if (request == null || request.password() == null || request.password().length() < 12
                || request.password().length() > 128) {
            throw invalidRequest();
        }
        String email = normalizeEmail(request.email());
        Optional<UUID> createdAccount = accounts.createAccount(email, passwords.encode(request.password()));
        if (createdAccount.isEmpty()) return new AccountActionResponse(true);
        UUID accountId = createdAccount.get();
        accounts.createBuyer(accountId);
        String token = newActionToken(accountId, "EMAIL_VERIFICATION", EMAIL_VERIFICATION_LIFETIME);
        sendAfterCommit(() -> emailSender.sendVerification(email, token));
        return new AccountActionResponse(true);
    }

    @Transactional
    public AccountActionResponse requestPasswordReset(String requestedEmail) {
        String email = normalizeEmail(requestedEmail);
        accounts.findVerifiedAccountIdByEmail(email).ifPresent(accountId -> {
            String token = newActionToken(accountId, "PASSWORD_RESET", PASSWORD_RESET_LIFETIME);
            sendAfterCommit(() -> emailSender.sendPasswordReset(email, token));
        });
        return new AccountActionResponse(true);
    }

    @Transactional
    public AccountActionResponse requestEmailVerification(String requestedEmail) {
        String email = normalizeEmail(requestedEmail);
        accounts.findUnverifiedAccountIdByEmail(email).ifPresent(accountId -> {
            String token = newActionToken(accountId, "EMAIL_VERIFICATION", EMAIL_VERIFICATION_LIFETIME);
            sendAfterCommit(() -> emailSender.sendVerification(email, token));
        });
        return new AccountActionResponse(true);
    }

    @Transactional
    public void verifyEmail(String presentedToken) {
        AccountActionToken token = requireActionToken(presentedToken, "EMAIL_VERIFICATION");
        Instant now = clock.instant();
        if (!now.isBefore(token.expiresAt()) || !accounts.consumeActionToken(token.tokenId(), now)) {
            throw invalidActionToken();
        }
        accounts.verifyEmail(token.accountId(), now);
    }

    @Transactional
    public void resetPassword(String presentedToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 128) {
            throw invalidRequest();
        }
        AccountActionToken token = requireActionToken(presentedToken, "PASSWORD_RESET");
        Instant now = clock.instant();
        if (!now.isBefore(token.expiresAt()) || !accounts.consumeActionToken(token.tokenId(), now)) {
            throw invalidActionToken();
        }
        accounts.updatePassword(token.accountId(), passwords.encode(newPassword), now);
        accounts.revokeAllRefreshFamilies(token.accountId(), now);
        accounts.revokeMfaChallenges(token.accountId(), now);
    }

    @Transactional
    public AccountLoginResponse login(LoginRequest request) {
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
        String currentPasswordHash = accounts.lockPasswordHashForLogin(email).orElseThrow(AccountService::invalidCredentials);
        if (!passwords.matches(request.password(), currentPasswordHash)) throw invalidCredentials();
        if (accounts.mfaEnabled(account.get().accountId())) {
            String challenge = newRefreshToken();
            Instant now = clock.instant();
            accounts.createMfaChallenge(hashRefreshToken(challenge), account.get().accountId(),
                    now, now.plus(MFA_CHALLENGE_LIFETIME));
            return new AccountLoginResponse(true, null, challenge);
        }
        return new AccountLoginResponse(false, issueTokens(account.get()), null);
    }

    @Transactional
    public MfaEnrollmentResponse startMfaEnrollment(UUID accountId) {
        AccountPrincipal account = accounts.findActiveById(accountId).orElseThrow(AccountService::invalidCredentials);
        if (accounts.mfaEnabled(accountId)) throw mfaConflict();
        String secret = new String(SecretGenerator.generate(), StandardCharsets.US_ASCII);
        if (!accounts.savePendingMfaSecret(accountId, mfaSecrets.encrypt(secret), clock.instant())) {
            throw mfaConflict();
        }
        try {
            String uri = new TOTPGenerator.Builder(secret).withClock(clock).build()
                    .getURI("ATLAS", account.email()).toString();
            return new MfaEnrollmentResponse(secret, uri);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("MFA enrollment URI could not be created", exception);
        }
    }

    @Transactional
    public MfaRecoveryCodesResponse confirmMfaEnrollment(UUID accountId, String code) {
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountService::invalidActionToken);
        if (secret.enabledAt() != null || !validTotp(accountId, secret.encryptedSecret(), code)) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        if (!accounts.enableMfa(accountId, now)) throw mfaConflict();
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String recoveryCode = newRecoveryCode();
            codes.add(recoveryCode);
            hashes.add(hashRefreshToken(recoveryCode));
        }
        accounts.replaceMfaRecoveryCodes(accountId, hashes, now);
        return new MfaRecoveryCodesResponse(List.copyOf(codes));
    }

    @Transactional
    public void disableMfa(UUID accountId, String code) {
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountService::invalidCredentials);
        if (secret.enabledAt() == null || !validTotp(accountId, secret.encryptedSecret(), code)) {
            throw invalidCredentials();
        }
        accounts.disableMfa(accountId);
    }

    @Transactional
    public AccountTokenResponse completeMfaLogin(MfaChallengeRequest request) {
        if (request == null || !StringUtils.hasText(request.challengeToken())
                || (StringUtils.hasText(request.code()) == StringUtils.hasText(request.recoveryCode()))) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        String challengeHash = hashRefreshToken(request.challengeToken());
        UUID accountId = accounts.findMfaChallengeForUpdate(challengeHash, now)
                .orElseThrow(AccountService::invalidCredentials);
        AccountPrincipal account = accounts.findActiveById(accountId).orElseThrow(AccountService::invalidCredentials);
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountService::invalidCredentials);
        boolean valid = secret.enabledAt() != null && (StringUtils.hasText(request.code())
                ? validTotp(accountId, secret.encryptedSecret(), request.code())
                : accounts.consumeMfaRecoveryCode(accountId, hashRefreshToken(request.recoveryCode()), now));
        if (!valid || !accounts.consumeMfaChallenge(challengeHash, now)) throw invalidCredentials();
        return issueTokens(account);
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
        return tokenResponse(account, nextToken, previous.familyId());
    }

    @Transactional(noRollbackFor = AccountException.class)
    public void logout(UUID accountId, UUID accessFamilyId, String refreshToken) {
        if (accountId == null || accessFamilyId == null || !StringUtils.hasText(refreshToken)) {
            throw invalidRequest();
        }
        RefreshTokenSession session = accounts.findRefreshSessionForUpdate(hashRefreshToken(refreshToken))
                .orElseThrow(AccountService::invalidRefreshToken);
        if (!accountId.equals(session.accountId()) || !accessFamilyId.equals(session.familyId())) {
            throw invalidRefreshToken();
        }
        accounts.revokeRefreshFamily(session.familyId(), clock.instant());
    }

    /**
     * 본인이 자기 계정을 폐쇄합니다.
     *
     * <p>폐쇄는 되돌릴 수 없으므로 비밀번호를 다시 확인합니다. 액세스 토큰만으로
     * 허용하면 탈취된 기기에서 악의적으로 계정을 닫을 수 있습니다.
     *
     * <p>한 트랜잭션에서 상태 변경과 모든 세션 폐기를 함께 수행합니다:
     * <ul>
     *   <li>{@code user_account.status = 'DISABLED'} — 이후 모든 검증이 거부됩니다.</li>
     *   <li>모든 refresh family 폐기 — 갱신 경로를 닫습니다.</li>
     *   <li>진행 중 MFA challenge 폐기 — 대기 중 인증을 무효화합니다.</li>
     * </ul>
     *
     * <p>응답 직후 같은 액세스 토큰이 401 이 되는 것은 검증기가 {@code status='ACTIVE'} 를
     * 요구하기 때문이며, 이 계약을 통합 테스트로 고정했습니다.
     *
     * <p>멱등성: 이미 폐쇄된 계정에 다시 요청하면 비밀번호가 맞아도 상태 변경 없이
     * 조용히 종료합니다(204). 호출자가 재시도해도 안전합니다.
     */
    @Transactional
    public void closeAccount(UUID accountId, String password) {
        if (accountId == null || !StringUtils.hasText(password)) throw invalidRequest();
        String passwordHash = accounts.findPasswordHashForClosure(accountId).orElseThrow(AccountService::invalidCredentials);
        if (!passwords.matches(password, passwordHash)) throw invalidCredentials();
        Instant now = clock.instant();
        if (!accounts.closeAccount(accountId, now)) return; // 이미 폐쇄됨: 멱등 종료
        accounts.revokeAllRefreshFamilies(accountId, now);
        accounts.revokeMfaChallenges(accountId, now);
    }

    /**
     * 운영자가 계정을 정지시킵니다.
     *
     * <p>본인 폐쇄와 달리 해제될 수 있고, 근거와 책임을 남겨야 합니다. 그래서
     * actor·reason·idempotency-key 를 모두 요구하고 감사 기록을 함께 씁니다.
     *
     * <p>정지하면 상태가 DISABLED 가 되어, 모든 로그인·갱신·액세스 검증이
     * 즉시 거부됩니다({@code isRefreshFamilyActive} 가 status='ACTIVE' 를 요구).
     * 진행 중 세션을 즉시 끊기 위해 refresh family 와 MFA challenge 도 폐기합니다.
     *
     * <p>멱등: 같은 idempotency-key 로 다시 요청하면 아무 것도 바꾸지 않고 종료합니다.
     * 운영 자동화가 재시도해도 감사 기록이 중복되지 않습니다.
     */
    @Transactional
    public void suspendAccount(UUID accountId, String reason, String actor, String idempotencyKey) {
        applySuspension(accountId, true, reason, actor, idempotencyKey);
    }

    /** 운영자가 계정 정지를 해제합니다. 감사 기록을 남기고 세션을 새로 발급받게 합니다. */
    @Transactional
    public void releaseAccount(UUID accountId, String reason, String actor, String idempotencyKey) {
        applySuspension(accountId, false, reason, actor, idempotencyKey);
    }

    private void applySuspension(UUID accountId, boolean suspend, String reason, String actor,
            String idempotencyKey) {
        if (accountId == null || !StringUtils.hasText(reason) || !StringUtils.hasText(actor)
                || !StringUtils.hasText(idempotencyKey) || reason.length() > 500) {
            throw invalidRequest();
        }
        if (!suspensions.accountExists(accountId)) {
            throw new AccountException(Code.INVALID_REQUEST, "Unknown account: " + accountId);
        }
        if (suspensions.actionRecorded(accountId, idempotencyKey)) return; // 재시도: 변화 없음
        Instant now = clock.instant();
        boolean changed = suspensions.apply(accountId, suspend, idempotencyKey, actor, reason, now);
        if (changed && suspend) {
            accounts.revokeAllRefreshFamilies(accountId, now);
            accounts.revokeMfaChallenges(accountId, now);
        }
        // 해제 시에는 세션이 이미 폐기됐으므로 재로그인으로 새 family 를 만든다.
    }

    /** 한 계정의 정지·해제 감사 기록입니다. 운영 확인용입니다. */
    public List<AccountSuspensionActionResponse> suspensionHistory(UUID accountId, int limit) {
        if (accountId == null) throw invalidRequest();
        return suspensions.history(accountId, limit <= 0 ? 20 : Math.min(limit, 100)).stream()
                .map(action -> new AccountSuspensionActionResponse(action.action(), action.idempotencyKey(),
                        action.actor(), action.reason(), action.createdAt()))
                .toList();
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

    public boolean isAccessTokenFamilyActive(UUID accountId, UUID familyId) {
        return accountId != null && familyId != null
                && accounts.isRefreshFamilyActive(accountId, familyId, clock.instant());
    }
    @Transactional(readOnly = true)
    public AccountSessionPage listSessions(
            UUID accountId, UUID currentFamilyId, int requestedLimit, int offset) {
        if (accountId == null || currentFamilyId == null) throw invalidRequest();
        int limit = requestedLimit == 0 ? 20 : requestedLimit;
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000) throw invalidRequest();
        List<ActiveRefreshFamily> fetched = accounts.findActiveRefreshFamilies(
                accountId, clock.instant(), limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<AccountSessionResponse> items = fetched.stream().limit(limit)
                .map(family -> new AccountSessionResponse(family.familyId(), family.createdAt(),
                        family.expiresAt(), family.familyId().equals(currentFamilyId)))
                .toList();
        return new AccountSessionPage(items, limit, offset, hasMore);
    }

    @Transactional
    public void revokeSession(UUID accountId, UUID familyId) {
        if (accountId == null || familyId == null) throw invalidRequest();
        accounts.revokeRefreshFamily(accountId, familyId, clock.instant());
    }


    private AccountTokenResponse issueTokens(AccountPrincipal account) {
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiresAt = now.plus(REFRESH_FAMILY_LIFETIME);
        String refreshToken = newRefreshToken();
        accounts.createRefreshFamily(familyId, account.accountId(), now, familyExpiresAt);
        accounts.insertRefreshSession(UUID.randomUUID(), familyId, hashRefreshToken(refreshToken), now,
                minimum(now.plus(REFRESH_TOKEN_LIFETIME), familyExpiresAt));
        return tokenResponse(account, refreshToken, familyId);
    }

    private String newActionToken(UUID accountId, String purpose, Duration lifetime) {
        String token = newRefreshToken();
        Instant now = clock.instant();
        accounts.replaceActionToken(accountId, purpose, hashRefreshToken(token), now, now.plus(lifetime));
        return token;
    }

    private AccountActionToken requireActionToken(String presentedToken, String purpose) {
        if (!StringUtils.hasText(presentedToken) || presentedToken.length() > 128) {
            throw invalidActionToken();
        }
        return accounts.findActionTokenForUpdate(hashRefreshToken(presentedToken), purpose)
                .filter(action -> action.consumedAt() == null)
                .orElseThrow(AccountService::invalidActionToken);
    }

    private boolean validTotp(UUID accountId, String encryptedSecret, String code) {
        if (!StringUtils.hasText(code) || !code.matches("[0-9]{6}")) return false;
        TOTPGenerator generator = new TOTPGenerator.Builder(mfaSecrets.decrypt(encryptedSecret))
                .withClock(clock).build();
        long currentStep = clock.instant().getEpochSecond() / generator.getPeriod().getSeconds();
        byte[] presented = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            Instant stepInstant = Instant.ofEpochSecond(step * generator.getPeriod().getSeconds());
            byte[] expected = generator.at(stepInstant).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(presented, expected)) return accounts.markMfaStepUsed(accountId, step);
        }
        return false;
    }

    private static String newRecoveryCode() {
        byte[] value = new byte[9];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void sendAfterCommit(Runnable delivery) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deliver(delivery);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(delivery);
            }
        });
    }

    private static void deliver(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Account email delivery failed; user can request a replacement token");
        }
    }

    private AccountTokenResponse tokenResponse(AccountPrincipal account, String refreshToken, UUID familyId) {
        return new AccountTokenResponse(accessTokens.issue(account.accountId(), familyId), refreshToken, "Bearer",
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

    private static AccountException invalidActionToken() {
        return new AccountException(Code.INVALID_ACTION_TOKEN, "Account action token is invalid or expired");
    }

    private static AccountException mfaConflict() {
        return new AccountException(Code.MFA_CONFLICT, "MFA enrollment state conflicts with this request");
    }
}