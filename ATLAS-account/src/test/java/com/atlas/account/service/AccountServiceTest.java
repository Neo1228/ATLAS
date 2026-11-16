package com.atlas.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.AccountActionToken;
import com.atlas.account.domain.AccountMfaSecret;
import com.atlas.account.domain.RefreshTokenSession;
import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.request.MfaChallengeRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.dto.response.AccountActionResponse;
import com.atlas.account.dto.response.AccountLoginResponse;
import com.atlas.account.dto.response.MfaEnrollmentResponse;
import com.atlas.account.dto.response.MfaRecoveryCodesResponse;
import com.atlas.account.repository.AccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.bastiaanjansen.otp.TOTPGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("fb9d9240-689a-4e83-82f9-617a54fd02f8");
    private static final UUID BUYER_ID = UUID.fromString("7ac7d463-bbcb-4f82-a967-85c4906093c3");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final AccountEmailSender emailSender = mock(AccountEmailSender.class);
    private final AccountMfaSecretProtector mfaSecrets = mock(AccountMfaSecretProtector.class);
    private final AtomicReference<String> enrollmentSecret = new AtomicReference<>();
    private AccountService service;

    @BeforeEach
    void setUp() {
        when(passwords.encode(any())).thenReturn("dummy-encoded-password");
        service = new AccountService(accounts, passwords, accessTokens, emailSender, mfaSecrets,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signupNormalizesEmailHashesPasswordAndCreatesBuyerIdentity() {
        when(accounts.createAccount("buyer@example.com", "encoded-password")).thenReturn(Optional.of(ACCOUNT_ID));
        when(passwords.encode("long-enough-password")).thenReturn("encoded-password");

        AccountActionResponse result = service.registerBuyer(
                new RegisterBuyerRequest(" Buyer@Example.com ", "long-enough-password"));

        assertTrue(result.accepted());
        verify(accounts).createBuyer(ACCOUNT_ID);
        verify(passwords).encode("long-enough-password");
        verify(accounts).replaceActionToken(eq(ACCOUNT_ID), eq("EMAIL_VERIFICATION"), anyString(), eq(NOW),
                eq(NOW.plus(Duration.ofHours(24))));
        verify(emailSender).sendVerification(eq("buyer@example.com"), anyString());
        verify(accessTokens, never()).issue(any());
    }

    @Test
    void signupDoesNotRevealDuplicateEmailOrCreateAnotherBuyer() {
        when(passwords.encode("long-enough-password")).thenReturn("encoded-password");
        when(accounts.createAccount("buyer@example.com", "encoded-password"))
                .thenReturn(Optional.empty());

        AccountActionResponse response = service.registerBuyer(
                new RegisterBuyerRequest("buyer@example.com", "long-enough-password"));

        assertTrue(response.accepted());
        verify(accounts, never()).createBuyer(any());
        verify(emailSender, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void signupReturnsTheSameAcceptedResponseForFreshAndExistingEmails() {
        when(accounts.createAccount("buyer@example.com", "dummy-encoded-password"))
                .thenReturn(Optional.of(ACCOUNT_ID)).thenReturn(Optional.empty());
        RegisterBuyerRequest request = new RegisterBuyerRequest("buyer@example.com", "long-enough-password");

        AccountActionResponse freshAccountResponse = service.registerBuyer(request);
        AccountActionResponse existingAccountResponse = service.registerBuyer(request);

        assertEquals(freshAccountResponse, existingAccountResponse);
        assertTrue(existingAccountResponse.accepted());
    }

    @Test
    void verifiesEmailOnlyAfterConsumingActionToken() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "EMAIL_VERIFICATION",
                NOW.plusSeconds(60), null);
        when(accounts.findActionTokenForUpdate(anyString(), eq("EMAIL_VERIFICATION")))
                .thenReturn(Optional.of(token));
        when(accounts.consumeActionToken(token.tokenId(), NOW)).thenReturn(true);

        service.verifyEmail("verification-token");

        verify(accounts).consumeActionToken(token.tokenId(), NOW);
        verify(accounts).verifyEmail(ACCOUNT_ID, NOW);
    }

    @Test
    void rejectsExpiredEmailVerificationTokenWithoutChangingAccountState() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "EMAIL_VERIFICATION",
                NOW.minusSeconds(1), null);
        when(accounts.findActionTokenForUpdate(anyString(), eq("EMAIL_VERIFICATION")))
                .thenReturn(Optional.of(token));

        AccountException error = assertThrows(AccountException.class, () -> service.verifyEmail("expired-token"));

        assertEquals(AccountException.Code.INVALID_ACTION_TOKEN, error.code());
        verify(accounts, never()).consumeActionToken(any(), any());
        verify(accounts, never()).verifyEmail(any(), any());
    }

    @Test
    void rejectsReplayedEmailVerificationTokenWithoutChangingAccountState() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "EMAIL_VERIFICATION",
                NOW.plusSeconds(60), NOW.minusSeconds(1));
        when(accounts.findActionTokenForUpdate(anyString(), eq("EMAIL_VERIFICATION")))
                .thenReturn(Optional.of(token));

        AccountException error = assertThrows(AccountException.class, () -> service.verifyEmail("replayed-token"));

        assertEquals(AccountException.Code.INVALID_ACTION_TOKEN, error.code());
        verify(accounts, never()).consumeActionToken(any(), any());
        verify(accounts, never()).verifyEmail(any(), any());
    }

    @Test
    void rejectsExpiredPasswordResetTokenWithoutChangingPasswordOrSessions() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "PASSWORD_RESET",
                NOW, null);
        when(accounts.findActionTokenForUpdate(anyString(), eq("PASSWORD_RESET"))).thenReturn(Optional.of(token));

        AccountException error = assertThrows(AccountException.class,
                () -> service.resetPassword("expired-token", "new-long-password"));

        assertEquals(AccountException.Code.INVALID_ACTION_TOKEN, error.code());
        verify(passwords, never()).encode("new-long-password");
        verify(accounts, never()).consumeActionToken(any(), any());
        verify(accounts, never()).updatePassword(any(), anyString(), any());
        verify(accounts, never()).revokeAllRefreshFamilies(any(), any());
        verify(accounts, never()).revokeMfaChallenges(any(), any());
    }

    @Test
    void rejectsReplayedPasswordResetTokenWithoutChangingPasswordOrSessions() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "PASSWORD_RESET",
                NOW.plusSeconds(60), NOW.minusSeconds(1));
        when(accounts.findActionTokenForUpdate(anyString(), eq("PASSWORD_RESET"))).thenReturn(Optional.of(token));

        AccountException error = assertThrows(AccountException.class,
                () -> service.resetPassword("replayed-token", "new-long-password"));

        assertEquals(AccountException.Code.INVALID_ACTION_TOKEN, error.code());
        verify(accounts, never()).consumeActionToken(any(), any());
        verify(accounts, never()).updatePassword(any(), anyString(), any());
        verify(accounts, never()).revokeAllRefreshFamilies(any(), any());
        verify(accounts, never()).revokeMfaChallenges(any(), any());
    }

    @Test
    void loginIssuesTokensForMatchingAccountCredentials() {
        when(accounts.findActiveByEmail("buyer@example.com")).thenReturn(Optional.of(principal()));
        when(accounts.findPasswordHash("buyer@example.com")).thenReturn(Optional.of("stored-password-hash"));
        when(accounts.lockPasswordHashForLogin("buyer@example.com"))
                .thenReturn(Optional.of("stored-password-hash"));
        when(passwords.matches("correct-password", "stored-password-hash")).thenReturn(true);
        when(accessTokens.issue(ACCOUNT_ID)).thenReturn("signed-access-token");

        AccountLoginResponse result = service.login(new LoginRequest("buyer@example.com", "correct-password"));

        assertEquals(false, result.mfaRequired());
        assertEquals(ACCOUNT_ID, result.tokens().accountId());
        assertEquals(BUYER_ID.toString(), result.tokens().buyerRef());
        assertEquals("signed-access-token", result.tokens().accessToken());
        assertEquals(600, result.tokens().expiresInSeconds());
    }

    @Test
    void unknownEmailUsesDummyHashBeforeGenericLoginFailure() {
        when(accounts.findActiveByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(accounts.findPasswordHash("missing@example.com")).thenReturn(Optional.empty());

        AccountException error = assertThrows(AccountException.class,
                () -> service.login(new LoginRequest("missing@example.com", "incorrect-password")));

        assertEquals(AccountException.Code.INVALID_CREDENTIALS, error.code());
        verify(accessTokens, never()).issue(any());
        verify(passwords).matches(eq("incorrect-password"), eq("dummy-encoded-password"));
        verify(accounts, never()).createRefreshFamily(any(), any(), any(), any());
    }

    @Test
    void wrongPasswordUsesSameGenericCredentialError() {
        when(accounts.findActiveByEmail("buyer@example.com")).thenReturn(Optional.of(principal()));
        when(accounts.findPasswordHash("buyer@example.com")).thenReturn(Optional.of("stored-password-hash"));
        when(passwords.matches("incorrect-password", "stored-password-hash")).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> service.login(new LoginRequest("buyer@example.com", "incorrect-password")));

        assertEquals(AccountException.Code.INVALID_CREDENTIALS, error.code());
        verify(accessTokens, never()).issue(any());
    }

    @Test
    void rejectsPasswordThatChangedBeforeTheLoginCouldLockTheAccount() {
        when(accounts.findActiveByEmail("buyer@example.com")).thenReturn(Optional.of(principal()));
        when(accounts.findPasswordHash("buyer@example.com")).thenReturn(Optional.of("old-password-hash"));
        when(accounts.lockPasswordHashForLogin("buyer@example.com"))
                .thenReturn(Optional.of("new-password-hash"));
        when(passwords.matches("old-password", "old-password-hash")).thenReturn(true);
        when(passwords.matches("old-password", "new-password-hash")).thenReturn(false);

        AccountException error = assertThrows(AccountException.class,
                () -> service.login(new LoginRequest("buyer@example.com", "old-password")));

        assertEquals(AccountException.Code.INVALID_CREDENTIALS, error.code());
        verify(accessTokens, never()).issue(any());
        verify(accounts, never()).createRefreshFamily(any(), any(), any(), any());
        verify(accounts, never()).createMfaChallenge(anyString(), any(), any(), any());
    }

    @Test
    void passwordResetRequestDoesNotRevealUnknownAccounts() {
        when(accounts.findVerifiedAccountIdByEmail("missing@example.com")).thenReturn(Optional.empty());

        AccountActionResponse response = service.requestPasswordReset("Missing@Example.com");

        assertTrue(response.accepted());
        verify(emailSender, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void passwordResetConsumesTokenUpdatesPasswordAndRevokesAllSessions() {
        AccountActionToken token = new AccountActionToken(UUID.randomUUID(), ACCOUNT_ID, "PASSWORD_RESET",
                NOW.plusSeconds(60), null);
        when(accounts.findActionTokenForUpdate(anyString(), eq("PASSWORD_RESET"))).thenReturn(Optional.of(token));
        when(accounts.consumeActionToken(token.tokenId(), NOW)).thenReturn(true);

        service.resetPassword("reset-token", "new-long-password");

        verify(passwords).encode("new-long-password");
        verify(accounts).updatePassword(eq(ACCOUNT_ID), eq("dummy-encoded-password"), eq(NOW));
        verify(accounts).revokeAllRefreshFamilies(ACCOUNT_ID, NOW);
        verify(accounts).revokeMfaChallenges(ACCOUNT_ID, NOW);
    }

    @Test
    void loginReturnsMfaChallengeWithoutIssuingAccessTokens() {
        when(accounts.findActiveByEmail("buyer@example.com")).thenReturn(Optional.of(principal()));
        when(accounts.findPasswordHash("buyer@example.com")).thenReturn(Optional.of("stored-password-hash"));
        when(accounts.lockPasswordHashForLogin("buyer@example.com"))
                .thenReturn(Optional.of("stored-password-hash"));
        when(passwords.matches("correct-password", "stored-password-hash")).thenReturn(true);
        when(accounts.mfaEnabled(ACCOUNT_ID)).thenReturn(true);

        AccountLoginResponse response = service.login(new LoginRequest("buyer@example.com", "correct-password"));

        assertTrue(response.mfaRequired());
        assertNotNull(response.challengeToken());
        verify(accessTokens, never()).issue(any());
        verify(accounts).createMfaChallenge(anyString(), eq(ACCOUNT_ID), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5))));
    }

    @Test
    void mfaEnrollmentCreatesSingleUseRecoveryCodes() {
        when(accounts.findActiveById(ACCOUNT_ID)).thenReturn(Optional.of(principal()));
        when(mfaSecrets.encrypt(anyString())).thenReturn("encrypted-secret");
        when(mfaSecrets.decrypt("encrypted-secret")).thenAnswer(invocation -> enrollmentSecret.get());
        when(accounts.savePendingMfaSecret(eq(ACCOUNT_ID), eq("encrypted-secret"), eq(NOW))).thenReturn(true);
        when(accounts.findMfaSecretForUpdate(ACCOUNT_ID))
                .thenAnswer(invocation -> Optional.of(new AccountMfaSecret("encrypted-secret", null)));
        when(accounts.markMfaStepUsed(eq(ACCOUNT_ID), anyLong())).thenReturn(true);
        when(accounts.enableMfa(ACCOUNT_ID, NOW)).thenReturn(true);

        MfaEnrollmentResponse enrollment = service.startMfaEnrollment(ACCOUNT_ID);
        enrollmentSecret.set(enrollment.secret());
        String code = new TOTPGenerator.Builder(enrollment.secret()).withClock(Clock.fixed(NOW, ZoneOffset.UTC))
                .build().now();

        MfaRecoveryCodesResponse response = service.confirmMfaEnrollment(ACCOUNT_ID, code);

        assertEquals(10, response.recoveryCodes().size());
        assertTrue(enrollment.otpauthUri().startsWith("otpauth://totp/"));
        verify(accounts).replaceMfaRecoveryCodes(eq(ACCOUNT_ID), any(), eq(NOW));
    }

    @Test
    void mfaRecoveryCodeCompletesChallengeAndIssuesTokens() {
        when(accounts.findMfaChallengeForUpdate(anyString(), eq(NOW))).thenReturn(Optional.of(ACCOUNT_ID));
        when(accounts.findActiveById(ACCOUNT_ID)).thenReturn(Optional.of(principal()));
        when(accounts.findMfaSecretForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountMfaSecret("encrypted-secret", NOW.minusSeconds(30))));
        when(accounts.consumeMfaRecoveryCode(eq(ACCOUNT_ID), anyString(), eq(NOW))).thenReturn(true);
        when(accounts.consumeMfaChallenge(anyString(), eq(NOW))).thenReturn(true);
        when(accessTokens.issue(ACCOUNT_ID)).thenReturn("mfa-access-token");

        AccountTokenResponse result = service.completeMfaLogin(
                new MfaChallengeRequest("challenge-token", null, "one-time-recovery-code"));

        assertEquals("mfa-access-token", result.accessToken());
        verify(accounts).consumeMfaRecoveryCode(eq(ACCOUNT_ID), anyString(), eq(NOW));
        verify(accounts).consumeMfaChallenge(anyString(), eq(NOW));
    }

    @Test
    void refreshRotatesTokenAndPersistsOnlyItsReplacementHash() {
        UUID familyId = UUID.randomUUID();
        UUID previousTokenId = UUID.randomUUID();
        Instant tokenExpiry = NOW.plus(Duration.ofDays(7));
        Instant familyExpiry = NOW.plus(Duration.ofDays(30));
        when(accounts.findRefreshSessionForUpdate(any()))
                .thenReturn(Optional.of(new RefreshTokenSession(previousTokenId, familyId, ACCOUNT_ID,
                        familyExpiry, null, tokenExpiry, null, null)));
        when(accounts.findActiveById(ACCOUNT_ID)).thenReturn(Optional.of(principal()));
        when(accounts.consumeRefreshSession(any(), any(), any())).thenReturn(true);
        when(accessTokens.issue(ACCOUNT_ID)).thenReturn("rotated-access-token");

        AccountTokenResponse result = service.refresh(new RefreshTokenRequest("presented-refresh-token"));

        assertEquals("rotated-access-token", result.accessToken());
        assertEquals(600, result.expiresInSeconds());
        assertNotEquals("presented-refresh-token", result.refreshToken());
        verify(accounts).consumeRefreshSession(any(), any(), eq(NOW));
        verify(accounts).insertRefreshSession(any(), eq(familyId), any(), eq(NOW), eq(tokenExpiry));
    }

    @Test
    void refreshTokenReplayRevokesItsFamily() {
        UUID familyId = UUID.randomUUID();
        Instant expiry = NOW.plusSeconds(3600);
        when(accounts.findRefreshSessionForUpdate(any()))
                .thenReturn(Optional.of(new RefreshTokenSession(
                        UUID.randomUUID(), familyId, ACCOUNT_ID, expiry, null, expiry, NOW.minusSeconds(1), null)));

        AccountException error = assertThrows(AccountException.class,
                () -> service.refresh(new RefreshTokenRequest("replayed-token")));

        assertEquals(AccountException.Code.REFRESH_TOKEN_REUSED, error.code());
        verify(accounts).revokeRefreshFamily(familyId, NOW);
    }

    private static AccountPrincipal principal() {
        return new AccountPrincipal(ACCOUNT_ID, "buyer@example.com", BUYER_ID, List.of());
    }
}