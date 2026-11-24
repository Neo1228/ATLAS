package com.atlas.account.service.impl;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.exception.AccountException.Code;
import com.atlas.account.exception.AccountException;
import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.model.AccountSuspensionActionType;
import com.atlas.account.model.AuthAttemptScope.SubjectBucket;
import com.atlas.account.model.AuthAttemptScope;
import com.atlas.account.persistence.projection.AccountActionToken;
import com.atlas.account.persistence.projection.AccountMfaSecret;
import com.atlas.account.persistence.projection.AccountPrincipal;
import com.atlas.account.persistence.projection.ActiveRefreshFamily;
import com.atlas.account.persistence.projection.RefreshTokenSession;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.repository.AccountSuspensionRepository;
import com.atlas.account.service.AccountEmailSender;
import com.atlas.account.service.AccountService;
import com.atlas.account.service.AuthAttemptLimiter;
import com.bastiaanjansen.otp.SecretGenerator;
import com.bastiaanjansen.otp.TOTPGenerator;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AccountServiceImpl implements AccountService {
    private static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final Duration REFRESH_FAMILY_LIFETIME = Duration.ofDays(30);
    private static final Duration EMAIL_VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_LIFETIME = Duration.ofMinutes(30);
    private static final Duration MFA_CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountServiceImpl.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AccessTokenService accessTokens;
    private final AccountEmailSender emailSender;
    private final AccountMfaSecretProtector mfaSecrets;
    private final Clock clock;
    private final AccountSuspensionRepository suspensions;
    private final AuthAttemptLimiter limiter;
    private final TransactionTemplate transactions;
    private final String dummyLoginPasswordHash;

    public AccountServiceImpl(AccountRepository accounts, PasswordEncoder passwords,
            AccessTokenService accessTokens, AccountEmailSender emailSender,
            AccountMfaSecretProtector mfaSecrets, Clock clock,
            AccountSuspensionRepository suspensions, AuthAttemptLimiter limiter,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.emailSender = emailSender;
        this.mfaSecrets = mfaSecrets;
        this.clock = clock;
        this.suspensions = suspensions;
        this.limiter = Objects.requireNonNull(limiter);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.dummyLoginPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Override
    public AccountResponse.Action registerBuyer(AccountRequest.RegisterBuyer request, String origin) {
        if (request == null || request.password() == null || request.password().length() < 12
                || request.password().length() > 128) {
            throw invalidRequest();
        }
        String email = normalizeEmail(request.email());
        // 가입도 인증 없이 호출 가능한 메일 발송 경로다. 남은 한도가 없으면 계정을 만들지 않는다.
        // 한도 확인과 비밀번호 해시(PBKDF2)는 DB 연결을 잡지 않은 채 transaction 밖에서 한다. 한도 저장은
        // 독립 transaction이라 바깥 transaction이 연결을 쥔 채 두 번째 연결을 요구하면 풀이 고갈된다.
        if (!limiter.tryAcquire(AuthAttemptScope.EMAIL, email, origin, clock.instant())) throw invalidRequest();
        String passwordHash = passwords.encode(request.password());
        return transactions.execute(status -> {
            Optional<UUID> createdAccount = accounts.createAccount(email, passwordHash);
            if (createdAccount.isEmpty()) return new AccountResponse.Action(true);
            UUID accountId = createdAccount.get();
            accounts.createBuyer(accountId);
            String token = newActionToken(accountId, AccountActionTokenPurpose.EMAIL_VERIFICATION, EMAIL_VERIFICATION_LIFETIME);
            sendAfterCommit(() -> emailSender.sendVerification(email, token));
            return new AccountResponse.Action(true);
        });
    }

    @Override
    public AccountResponse.Action requestPasswordReset(String requestedEmail, String origin) {
        String email = normalizeEmail(requestedEmail);
        Instant now = clock.instant();
        if (!limiter.tryAcquire(AuthAttemptScope.EMAIL, email, origin, now)) return new AccountResponse.Action(true);
        return transactions.execute(status -> {
            accounts.findVerifiedAccountIdByEmail(email).ifPresent(accountId -> {
                String token = newActionToken(accountId, AccountActionTokenPurpose.PASSWORD_RESET, PASSWORD_RESET_LIFETIME);
                sendAfterCommit(() -> emailSender.sendPasswordReset(email, token));
            });
            return new AccountResponse.Action(true);
        });
    }

    @Override
    public AccountResponse.Action requestEmailVerification(String requestedEmail, String origin) {
        String email = normalizeEmail(requestedEmail);
        Instant now = clock.instant();
        if (!limiter.tryAcquire(AuthAttemptScope.EMAIL, email, origin, now)) return new AccountResponse.Action(true);
        return transactions.execute(status -> {
            accounts.findUnverifiedAccountIdByEmail(email).ifPresent(accountId -> {
                String token = newActionToken(accountId, AccountActionTokenPurpose.EMAIL_VERIFICATION, EMAIL_VERIFICATION_LIFETIME);
                sendAfterCommit(() -> emailSender.sendVerification(email, token));
            });
            return new AccountResponse.Action(true);
        });
    }

    @Transactional
    @Override
    public void verifyEmail(String presentedToken) {
        AccountActionToken token = requireActionToken(presentedToken, AccountActionTokenPurpose.EMAIL_VERIFICATION);
        Instant now = clock.instant();
        if (!now.isBefore(token.expiresAt()) || !accounts.consumeActionToken(token.tokenId(), now)) {
            throw invalidActionToken();
        }
        accounts.verifyEmail(token.accountId(), now);
    }

    @Override
    public void resetPassword(String presentedToken, String newPassword) {
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 128) {
            throw invalidRequest();
        }
        String email = transactions.execute(status -> applyPasswordReset(presentedToken, newPassword));
        if (email == null) return;
        try {
            limiter.resetSubject(SubjectBucket.LOGIN, normalizeEmail(email));
        } catch (RuntimeException exception) {
            LOGGER.warn("Login lockout counter was not cleared after password reset: failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private String applyPasswordReset(String presentedToken, String newPassword) {
        AccountActionToken token = requireActionToken(presentedToken, AccountActionTokenPurpose.PASSWORD_RESET);
        Instant now = clock.instant();
        if (!now.isBefore(token.expiresAt()) || !accounts.consumeActionToken(token.tokenId(), now)) {
            throw invalidActionToken();
        }
        accounts.updatePassword(token.accountId(), passwords.encode(newPassword), now);
        accounts.revokeAllRefreshFamilies(token.accountId(), now);
        accounts.revokeMfaChallenges(token.accountId(), now);
        return accounts.findActiveById(token.accountId()).map(AccountPrincipal::email).orElse(null);
    }

    @Override
    public AccountResponse.Login login(AccountRequest.Login request, String origin) {
        if (request == null || request.password() == null || request.password().length() > 128) {
            throw invalidCredentials();
        }
        String email = normalizeEmail(request.email());
        Instant now = clock.instant();
        // 비밀번호 검증(PBKDF2)은 수십 ms의 CPU를 쓴다. 한도 확인은 반드시 그 앞에서 해야
        // 자원 고갈을 막을 수 있다. 응답은 실제 인증 실패와 구분되지 않게 같은 예외를 쓴다.
        // 한도 저장은 독립 transaction이므로 바깥 transaction 없이 호출한다. 로그인 전체를 하나의
        // transaction으로 묶으면 연결 1개를 쥔 채 두 번째 연결을 기다려, 동시 로그인이 풀 크기를
        // 넘는 순간 모든 요청이 서로를 기다리며 실패한다. PBKDF2도 연결을 쥐지 않은 채 계산한다.
        if (!limiter.tryAcquire(AuthAttemptScope.LOGIN, email, origin == null ? "unknown" : origin, now)) {
            throw invalidCredentials();
        }
        Optional<AccountPrincipal> account = accounts.findActiveByEmail(email);
        Optional<String> storedPasswordHash = accounts.findPasswordHash(email);
        String passwordHash = storedPasswordHash.orElse(dummyLoginPasswordHash);
        boolean passwordMatches = passwords.matches(request.password(), passwordHash);
        if (account.isEmpty() || storedPasswordHash.isEmpty() || !passwordMatches
                || account.get().buyerId() == null) {
            limiter.recordSubjectFailure(SubjectBucket.LOGIN, email, now);
            throw invalidCredentials();
        }
        AccountResponse.Login response = transactions.execute(status ->
                completeLogin(email, request.password(), passwordHash, account.get()));
        if (response == null) {
            limiter.recordSubjectFailure(SubjectBucket.LOGIN, email, now);
            throw invalidCredentials();
        }
        limiter.resetSubject(SubjectBucket.LOGIN, email);
        return response;
    }

    /**
     * 검증한 비밀번호 해시가 여전히 현재 값인지 잠금 아래에서 확인하고 토큰을 발급한다. 확인 사이에 비밀번호가
     * 바뀌었을 때만 다시 계산한다. 같은 해시와 같은 비밀번호의 검증 결과는 같으므로 평소에는 PBKDF2를
     * 반복하지 않는다. 인증에 실패하면 {@code null}을 돌려주고 실패 기록은 호출자가 transaction 밖에서 한다.
     */
    private AccountResponse.Login completeLogin(String email, String password, String verifiedHash,
            AccountPrincipal account) {
        Optional<String> currentPasswordHash = accounts.lockPasswordHashForLogin(email);
        if (currentPasswordHash.isEmpty()) return null;
        if (!currentPasswordHash.get().equals(verifiedHash) && !passwords.matches(password, currentPasswordHash.get())) {
            return null;
        }
        if (accounts.mfaEnabled(account.accountId())) {
            String challenge = newRefreshToken();
            Instant challengeNow = clock.instant();
            accounts.createMfaChallenge(hashRefreshToken(challenge), account.accountId(),
                    challengeNow, challengeNow.plus(MFA_CHALLENGE_LIFETIME));
            return new AccountResponse.Login(true, null, challenge);
        }
        return new AccountResponse.Login(false, issueTokens(account), null);
    }

    @Transactional
    @Override
    public AccountResponse.MfaEnrollment startMfaEnrollment(UUID accountId) {
        AccountPrincipal account = accounts.findActiveById(accountId).orElseThrow(AccountServiceImpl::invalidCredentials);
        if (accounts.mfaEnabled(accountId)) throw mfaConflict();
        String secret = new String(SecretGenerator.generate(), StandardCharsets.US_ASCII);
        if (!accounts.savePendingMfaSecret(accountId, mfaSecrets.encrypt(secret), clock.instant())) {
            throw mfaConflict();
        }
        try {
            String uri = new TOTPGenerator.Builder(secret).withClock(clock).build()
                    .getURI("ATLAS", account.email()).toString();
            return new AccountResponse.MfaEnrollment(secret, uri);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("MFA enrollment URI could not be created", exception);
        }
    }

    @Transactional
    @Override
    public AccountResponse.MfaRecoveryCodes confirmMfaEnrollment(UUID accountId, String code) {
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountServiceImpl::invalidActionToken);
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
        return new AccountResponse.MfaRecoveryCodes(List.copyOf(codes));
    }

    @Transactional
    @Override
    public void disableMfa(UUID accountId, String code) {
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountServiceImpl::invalidCredentials);
        if (secret.enabledAt() == null || !validTotp(accountId, secret.encryptedSecret(), code)) {
            throw invalidCredentials();
        }
        accounts.disableMfa(accountId);
    }

    @Override
    public AccountResponse.Token completeMfaLogin(AccountRequest.MfaChallenge request) {
        if (request == null || !StringUtils.hasText(request.challengeToken())
                || (StringUtils.hasText(request.code()) == StringUtils.hasText(request.recoveryCode()))) {
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        String challengeHash = hashRefreshToken(request.challengeToken());
        // 챌린지 TTL(5분) 안에서 6자리 코드를 무제한 시도할 수 있으면 MFA가 사실상 무력화된다.
        // 실패 한도를 넘으면 챌린지를 소모시켜 같은 챌린지로 계속 시도하지 못하게 한다.
        // 한도 저장은 독립 transaction이라 챌린지·비밀 행 잠금을 쥔 채 호출하면 연결을 둘 잡는다. 동시 요청이
        // 풀 크기를 넘으면 모든 요청이 연결 1개와 행 잠금을 쥔 채 두 번째 연결을 기다려 붕괴한다. 그래서 대상
        // 계정을 잠금 없이 먼저 읽어 한도를 확인하고, 코드 검증과 챌린지 소모만 잠금 아래 짧게 수행한다.
        UUID accountId = accounts.findMfaChallengeAccount(challengeHash, now).orElseThrow(AccountServiceImpl::invalidCredentials);
        String mfaSubject = "mfa:" + accountId;
        if (!limiter.tryAcquire(AuthAttemptScope.MFA, mfaSubject, mfaSubject, now)) {
            transactions.executeWithoutResult(status -> accounts.consumeMfaChallenge(challengeHash, now));
            throw invalidCredentials();
        }
        AccountResponse.Token tokens = transactions.execute(status ->
                verifyMfaAndIssue(accountId, challengeHash, request, now));
        if (tokens == null) {
            limiter.recordSubjectFailure(SubjectBucket.MFA, mfaSubject, now);
            throw invalidCredentials();
        }
        limiter.resetSubject(SubjectBucket.MFA, mfaSubject);
        return tokens;
    }

    /**
     * 챌린지·비밀 행을 잠그고 코드를 검증해 토큰을 발급한다. 검증에 실패하면 {@code null}을 돌려주고 실패 기록은
     * 호출자가 transaction 밖에서 한다. 챌린지는 코드가 맞을 때만 소모하므로 틀린 코드는 같은 챌린지로 다시 시도할
     * 수 있고, 시도 횟수는 한도가 제한한다.
     */
    private AccountResponse.Token verifyMfaAndIssue(UUID accountId, String challengeHash,
            AccountRequest.MfaChallenge request, Instant now) {
        Optional<UUID> locked = accounts.findMfaChallengeForUpdate(challengeHash, now);
        if (locked.isEmpty() || !locked.get().equals(accountId)) {
            throw invalidCredentials();
        }
        AccountPrincipal account = accounts.findActiveById(accountId).orElseThrow(AccountServiceImpl::invalidCredentials);
        AccountMfaSecret secret = accounts.findMfaSecretForUpdate(accountId).orElseThrow(AccountServiceImpl::invalidCredentials);
        boolean valid = secret.enabledAt() != null && (StringUtils.hasText(request.code())
                ? validTotp(accountId, secret.encryptedSecret(), request.code())
                : accounts.consumeMfaRecoveryCode(accountId, hashRefreshToken(request.recoveryCode()), now));
        if (!valid) {
            return null;
        }
        // 코드는 맞았는데 챌린지를 소모하지 못하면(만료 직전 경합) 방금 소모한 복구 코드와 TOTP 사용 기록이 남지
        // 않도록 예외로 transaction을 롤백한다. 코드가 맞았으므로 실패 횟수에는 포함하지 않는다.
        if (!accounts.consumeMfaChallenge(challengeHash, now)) {
            throw invalidCredentials();
        }
        return issueTokens(account);
    }

    @Transactional(noRollbackFor = AccountException.class)
    @Override
    public AccountResponse.Token refresh(AccountRequest.RefreshToken request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())) throw invalidRefreshToken();
        Instant now = clock.instant();
        RefreshTokenSession previous = accounts.findRefreshSessionForUpdate(hashRefreshToken(request.refreshToken()))
                .orElseThrow(AccountServiceImpl::invalidRefreshToken);
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
    @Override
    public void logout(UUID accountId, UUID accessFamilyId, String refreshToken) {
        if (accountId == null || accessFamilyId == null || !StringUtils.hasText(refreshToken)) {
            throw invalidRequest();
        }
        RefreshTokenSession session = accounts.findRefreshSessionForUpdate(hashRefreshToken(refreshToken))
                .orElseThrow(AccountServiceImpl::invalidRefreshToken);
        if (!accountId.equals(session.accountId()) || !accessFamilyId.equals(session.familyId())) {
            throw invalidRefreshToken();
        }
        accounts.revokeRefreshFamily(session.familyId(), clock.instant());
    }

    @Transactional
    @Override
    public void closeAccount(UUID accountId, String password) {
        if (accountId == null || !StringUtils.hasText(password)) throw invalidRequest();
        String passwordHash = accounts.findPasswordHashForClosure(accountId).orElseThrow(AccountServiceImpl::invalidCredentials);
        if (!passwords.matches(password, passwordHash)) throw invalidCredentials();
        Instant now = clock.instant();
        if (!accounts.closeAccount(accountId, now)) return; // 이미 폐쇄됨: 멱등 종료
        accounts.revokeAllRefreshFamilies(accountId, now);
        accounts.revokeMfaChallenges(accountId, now);
    }

    @Transactional
    @Override
    public void suspendAccount(UUID accountId, String reason, String actor, String idempotencyKey) {
        applySuspension(accountId, AccountSuspensionActionType.SUSPEND, reason, actor, idempotencyKey);
    }

    @Transactional
    @Override
    public void releaseAccount(UUID accountId, String reason, String actor, String idempotencyKey) {
        applySuspension(accountId, AccountSuspensionActionType.RELEASE, reason, actor, idempotencyKey);
    }

    private void applySuspension(UUID accountId, AccountSuspensionActionType action, String reason, String actor,
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
        boolean changed = suspensions.apply(accountId, action, idempotencyKey, actor, reason, now);
        if (changed && action == AccountSuspensionActionType.SUSPEND) {
            accounts.revokeAllRefreshFamilies(accountId, now);
            accounts.revokeMfaChallenges(accountId, now);
        }
        // 해제 시에는 세션이 이미 폐기됐으므로 재로그인으로 새 family 를 만든다.
    }

    @Override
    public List<AccountResponse.SuspensionAction> suspensionHistory(UUID accountId, int limit) {
        if (accountId == null) throw invalidRequest();
        return suspensions.history(accountId, limit <= 0 ? 20 : Math.min(limit, 100)).stream()
                .map(action -> new AccountResponse.SuspensionAction(action.action(), action.idempotencyKey(),
                        action.actor(), action.reason(), action.createdAt()))
                .toList();
    }

    @Transactional
    @Override
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
    @Override
    public boolean ownsBuyer(UUID accountId, String buyerRef) {
        return accountId != null && StringUtils.hasText(buyerRef) && accounts.ownsBuyer(accountId, buyerRef);
    }

    @Transactional(readOnly = true)
    @Override
    public boolean ownsSeller(UUID accountId, UUID sellerId) {
        return accountId != null && sellerId != null && accounts.ownsSeller(accountId, sellerId);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<AccountResponse.VerifiedSellerAccount> findVerifiedSellerAccount(UUID accountId, UUID sellerId) {
        if (accountId == null || sellerId == null || !accounts.ownsSeller(accountId, sellerId)) {
            throw new AccountException(Code.RESOURCE_NOT_OWNED, "Seller identity is not available");
        }
        return accounts.findVerifiedSellerAccount(accountId, sellerId)
                .map(account -> new AccountResponse.VerifiedSellerAccount(account.accountId(), account.email()));
    }

    @Transactional(readOnly = true)
    @Override
    public void requireSellerOwnership(UUID accountId, UUID sellerId) {
        if (!ownsSeller(accountId, sellerId)) {
            throw new AccountException(Code.RESOURCE_NOT_OWNED, "Seller profile is not available to this account");
        }
    }

    @Transactional(readOnly = true)
    @Override
    public String requireOwnBuyerRef(UUID accountId, String requestedBuyerRef) {
        String buyerRef = buyerRef(accountId);
        if (!buyerRef.equals(requestedBuyerRef)) {
            throw new AccountException(Code.RESOURCE_NOT_OWNED, "Buyer identity is not available to this account");
        }
        return buyerRef;
    }

    @Transactional(readOnly = true)
    @Override
    public String buyerRef(UUID accountId) {
        return accounts.findBuyerRef(accountId).orElseThrow(() ->
                new AccountException(Code.RESOURCE_NOT_OWNED, "Buyer identity is unavailable"));
    }

    @Override
    public boolean isAccessTokenFamilyActive(UUID accountId, UUID familyId) {
        return accountId != null && familyId != null
                && accounts.isRefreshFamilyActive(accountId, familyId, clock.instant());
    }
    @Transactional(readOnly = true)
    @Override
    public AccountResponse.SessionPage listSessions(
            UUID accountId, UUID currentFamilyId, int requestedLimit, int offset) {
        if (accountId == null || currentFamilyId == null) throw invalidRequest();
        int limit = requestedLimit == 0 ? 20 : requestedLimit;
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000) throw invalidRequest();
        List<ActiveRefreshFamily> fetched = accounts.findActiveRefreshFamilies(
                accountId, clock.instant(), limit + 1, offset);
        boolean hasMore = fetched.size() > limit;
        List<AccountResponse.Session> items = fetched.stream().limit(limit)
                .map(family -> new AccountResponse.Session(family.familyId(), family.createdAt(),
                        family.expiresAt(), family.familyId().equals(currentFamilyId)))
                .toList();
        return new AccountResponse.SessionPage(items, limit, offset, hasMore);
    }

    @Transactional
    @Override
    public void revokeSession(UUID accountId, UUID familyId) {
        if (accountId == null || familyId == null) throw invalidRequest();
        accounts.revokeRefreshFamily(accountId, familyId, clock.instant());
    }

    private AccountResponse.Token issueTokens(AccountPrincipal account) {
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiresAt = now.plus(REFRESH_FAMILY_LIFETIME);
        String refreshToken = newRefreshToken();
        accounts.createRefreshFamily(familyId, account.accountId(), now, familyExpiresAt);
        accounts.insertRefreshSession(UUID.randomUUID(), familyId, hashRefreshToken(refreshToken), now,
                minimum(now.plus(REFRESH_TOKEN_LIFETIME), familyExpiresAt));
        return tokenResponse(account, refreshToken, familyId);
    }

    private String newActionToken(UUID accountId, AccountActionTokenPurpose purpose, Duration lifetime) {
        String token = newRefreshToken();
        Instant now = clock.instant();
        accounts.replaceActionToken(accountId, purpose, hashRefreshToken(token), now, now.plus(lifetime));
        return token;
    }

    private AccountActionToken requireActionToken(String presentedToken,
            AccountActionTokenPurpose purpose) {
        if (!StringUtils.hasText(presentedToken) || presentedToken.length() > 128) {
            throw invalidActionToken();
        }
        return accounts.findActionTokenForUpdate(hashRefreshToken(presentedToken), purpose)
                .filter(action -> action.consumedAt() == null)
                .orElseThrow(AccountServiceImpl::invalidActionToken);
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

    private AccountResponse.Token tokenResponse(AccountPrincipal account, String refreshToken, UUID familyId) {
        return new AccountResponse.Token(accessTokens.issue(account.accountId(), familyId), refreshToken, "Bearer",
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