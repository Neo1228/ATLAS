package com.atlas.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.account.domain.AccountException;
import com.atlas.account.domain.AccountPrincipal;
import com.atlas.account.domain.RefreshTokenSession;
import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.dto.request.RefreshTokenRequest;
import com.atlas.account.dto.request.RegisterBuyerRequest;
import com.atlas.account.dto.response.AccountTokenResponse;
import com.atlas.account.repository.AccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("fb9d9240-689a-4e83-82f9-617a54fd02f8");
    private static final UUID BUYER_ID = UUID.fromString("7ac7d463-bbcb-4f82-a967-85c4906093c3");

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private AccountService service;

    @BeforeEach
    void setUp() {
        when(passwords.encode(any())).thenReturn("dummy-encoded-password");
        service = new AccountService(accounts, passwords, accessTokens,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signupNormalizesEmailHashesPasswordAndCreatesBuyerIdentity() {
        when(accounts.createAccount("buyer@example.com", "encoded-password")).thenReturn(ACCOUNT_ID);
        when(accounts.findActiveById(ACCOUNT_ID)).thenReturn(Optional.of(principal()));
        when(passwords.encode("long-enough-password")).thenReturn("encoded-password");
        when(accessTokens.issue(ACCOUNT_ID)).thenReturn("signed-access-token");

        AccountTokenResponse result = service.registerBuyer(
                new RegisterBuyerRequest(" Buyer@Example.com ", "long-enough-password"));

        assertEquals(ACCOUNT_ID, result.accountId());
        assertEquals(BUYER_ID.toString(), result.buyerRef());
        assertEquals("signed-access-token", result.accessToken());
        assertEquals("Bearer", result.tokenType());
        assertEquals(600, result.expiresInSeconds());
        verify(accounts).createBuyer(ACCOUNT_ID);
        verify(passwords).encode("long-enough-password");
    }

    @Test
    void signupRejectsDuplicateEmailWithoutCreatingBuyer() {
        when(passwords.encode("long-enough-password")).thenReturn("encoded-password");
        when(accounts.createAccount("buyer@example.com", "encoded-password"))
                .thenThrow(new DataIntegrityViolationException("unique email"));

        AccountException error = assertThrows(AccountException.class, () -> service.registerBuyer(
                new RegisterBuyerRequest("buyer@example.com", "long-enough-password")));

        assertEquals(AccountException.Code.EMAIL_ALREADY_USED, error.code());
        verify(accounts, never()).createBuyer(any());
    }

    @Test
    void loginIssuesTokensForMatchingAccountCredentials() {
        when(accounts.findActiveByEmail("buyer@example.com")).thenReturn(Optional.of(principal()));
        when(accounts.findPasswordHash("buyer@example.com")).thenReturn(Optional.of("stored-password-hash"));
        when(passwords.matches("correct-password", "stored-password-hash")).thenReturn(true);
        when(accessTokens.issue(ACCOUNT_ID)).thenReturn("signed-access-token");

        AccountTokenResponse result = service.login(new LoginRequest("buyer@example.com", "correct-password"));

        assertEquals(ACCOUNT_ID, result.accountId());
        assertEquals(BUYER_ID.toString(), result.buyerRef());
        assertEquals("signed-access-token", result.accessToken());
        assertEquals(600, result.expiresInSeconds());
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
