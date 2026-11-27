package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.exception.AccountException;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountEmailSender;
import com.atlas.account.service.AccountService;
import com.nimbusds.jwt.SignedJWT;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
@Import(AccountAuthenticationIntegrationTest.TestEmailConfiguration.class)
class AccountAuthenticationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccountEmailSender testEmailSender;

    @Test
    void counterStoreFailureIsVisibleAsAServerErrorNotAsAFakeCredentialRejection() throws Exception {
        // 인증 카운터 저장소가 죽으면 실제 장애를 "비밀번호가 틀렸다"로 위장하지 않아야 한다.
        // 401로 위장하면 운영자는 원인을 찾지 못하고 사용자는 재시도만 반복한다.
        // 주입: 카운터 테이블을 잠시 사라지게 해 실제 저장소 오류(relation missing)를 만든다.
        // 잠금 대기 방식은 요청 스레드의 connection 에 lock_timeout 이 걸려야 해서 결정적이지 않다.
        String email = "counter-down-" + UUID.randomUUID() + "@example.com";
        jdbc.execute("ALTER TABLE auth_origin_bucket RENAME TO auth_origin_bucket_unavailable");
        try {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"long-enough-password-123\"}"))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("카운터 저장소 장애는 가시적 5xx여야 한다")
                            .isGreaterThanOrEqualTo(500))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbc.execute("ALTER TABLE auth_origin_bucket_unavailable RENAME TO auth_origin_bucket");
        }
    }

    @Test
    void normalizedEmailConflictDoesNotCreateAnotherAccount() {
        String email = "Buyer-" + UUID.randomUUID() + "@example.com";

        assertThat(accounts.createAccount(email, "first-hash")).isPresent();
        assertThat(accounts.createAccount(email.toLowerCase(), "second-hash")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE lower(email) = lower(?)",
                Long.class, email)).isEqualTo(1L);
    }

    @Test
    void passwordResetInvalidationMakesOutstandingMfaChallengeUnusable() {
        String email = "mfa-reset-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, "password-hash").orElseThrow();
        Instant now = Instant.now();
        String challengeHash = "a".repeat(64);
        accounts.createMfaChallenge(challengeHash, accountId, now, now.plusSeconds(300));

        assertThat(accounts.findMfaChallengeForUpdate(challengeHash, now)).contains(accountId);

        accounts.revokeMfaChallenges(accountId, now.plusSeconds(1));

        assertThat(accounts.findMfaChallengeForUpdate(challengeHash, now.plusSeconds(1))).isEmpty();
    }

    @Test
    void loginCredentialRowLockBlocksConcurrentPasswordReset() throws Exception {
        String email = "login-lock-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, "old-hash").orElseThrow();
        Instant now = Instant.now();
        accounts.verifyEmail(accountId, now);
        CountDownLatch passwordLocked = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        CountDownLatch resetStatementStarting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Void> loginTransaction = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(accounts.lockPasswordHashForLogin(email)).contains("old-hash");
                passwordLocked.countDown();
                await(releaseLogin);
                return null;
            }));
            assertThat(passwordLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> resetAttempt = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                        resetStatementStarting.countDown();
                        accounts.updatePassword(accountId, "new-hash", now.plusSeconds(1));
                        return null;
                    });
                    return false;
                } catch (UncategorizedSQLException exception) {
                    return exception.getSQLException() != null
                            && "55P03".equals(exception.getSQLException().getSQLState());
                }
            });
            assertThat(resetStatementStarting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(resetAttempt.get(5, TimeUnit.SECONDS)).isTrue();

            releaseLogin.countDown();
            loginTransaction.get(5, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).execute(status -> {
                accounts.updatePassword(accountId, "new-hash", now.plusSeconds(1));
                return null;
            });
            assertThat(accounts.findPasswordHash(email)).contains("new-hash");
        } finally {
            releaseLogin.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logoutRevokesOnlyItsAccessTokenFamilyAndAllFamilyRevocationInvalidatesEveryToken() throws Exception {
        String email = "jwt-family-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        String buyerRef = accountService.buyerRef(accountId);
        AccountResponse.Login firstLogin = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        AccountResponse.Login secondLogin = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        String cartPath = "/api/buyers/" + buyerRef + "/cart";

        assertThat(firstLogin.mfaRequired()).isFalse();
        assertThat(secondLogin.mfaRequired()).isFalse();
        mockMvc.perform(get(cartPath).header("Authorization", "Bearer " + firstLogin.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerRef").value(buyerRef))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(cartPath).header("Authorization", "Bearer " + secondLogin.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopping_cart WHERE buyer_ref = ?",
                Integer.class, buyerRef)).isZero();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + firstLogin.tokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + firstLogin.tokens().refreshToken() + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(cartPath).header("Authorization", "Bearer " + firstLogin.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(cartPath).header("Authorization", "Bearer " + secondLogin.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        accounts.revokeAllRefreshFamilies(accountId, Instant.now());

        mockMvc.perform(get(cartPath).header("Authorization", "Bearer " + secondLogin.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotationKeepsFamilyAndReplayRevokesAllFamilyAccessTokens() throws Exception {
        String email = "refresh-replay-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        AccountResponse.Login initial = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        UUID familyId = UUID.fromString(SignedJWT.parse(initial.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));

        AccountResponse.Token rotated = accountService.refresh(new AccountRequest.RefreshToken(initial.tokens().refreshToken()));
        assertThat(UUID.fromString(SignedJWT.parse(rotated.accessToken())
                .getJWTClaimsSet().getStringClaim("sid"))).isEqualTo(familyId);
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + initial.tokens().accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> accountService.refresh(new AccountRequest.RefreshToken(initial.tokens().refreshToken())))
                .isInstanceOf(AccountException.class);

        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + initial.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void measuresIndexedRefreshFamilyRevocationLookupWithRepresentativeAccountCardinality() {
        String email = "jwt-measure-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, "test-password-hash").orElseThrow();
        accounts.createBuyer(accountId);
        Instant now = Instant.now();
        accounts.verifyEmail(accountId, now);
        UUID targetFamilyId = UUID.randomUUID();
        accounts.createRefreshFamily(targetFamilyId, accountId, now, now.plusSeconds(3600), false);

        List<Object[]> families = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) {
            families.add(new Object[] {UUID.randomUUID(), accountId,
                    Timestamp.from(now.minusSeconds(i + 1L)), Timestamp.from(now.plusSeconds(3600))});
        }
        jdbc.batchUpdate("""
                INSERT INTO refresh_token_family (family_id, account_id, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, families);
        jdbc.execute("ANALYZE refresh_token_family");

        assertThat(accountService.isAccessTokenFamilyActive(accountId, targetFamilyId)).isTrue();
        long[] elapsedNanos = new long[200];
        for (int i = 0; i < elapsedNanos.length; i++) {
            long startedAt = System.nanoTime();
            assertThat(accountService.isAccessTokenFamilyActive(accountId, targetFamilyId)).isTrue();
            elapsedNanos[i] = System.nanoTime() - startedAt;
        }
        Arrays.sort(elapsedNanos);
        double p50Millis = elapsedNanos[elapsedNanos.length / 2] / 1_000_000.0;
        double p95Millis = elapsedNanos[(elapsedNanos.length * 95) / 100] / 1_000_000.0;
        List<String> plan = jdbc.query("""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                SELECT EXISTS (
                    SELECT 1
                    FROM refresh_token_family family
                    JOIN user_account account ON account.account_id = family.account_id
                    WHERE family.family_id = ?
                      AND family.account_id = ?
                      AND family.revoked_at IS NULL
                      AND family.expires_at > ?
                      AND account.status = 'ACTIVE'
                      AND account.email_verified_at IS NOT NULL
                )
                """, (rs, rowNum) -> rs.getString(1),
                targetFamilyId, accountId, Timestamp.from(Instant.now()));
        String explain = String.join(" | ", plan);
        assertThat(explain).contains("refresh_token_family_pkey");
        List<String> sessionPlan = jdbc.query("""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                SELECT family_id, created_at, expires_at
                FROM refresh_token_family
                WHERE account_id = ? AND revoked_at IS NULL AND expires_at > ?
                ORDER BY created_at DESC, family_id DESC
                LIMIT 21 OFFSET 0
                """, (rs, rowNum) -> rs.getString(1), accountId, Timestamp.from(Instant.now()));
        String sessionExplain = String.join(" | ", sessionPlan);
        assertThat(sessionExplain).contains("ix_refresh_family_active_account_created");
        System.out.printf(Locale.ROOT,
                "JWT family status p50=%.3fms p95=%.3fms; session page plan=%s%n",
                p50Millis, p95Millis, sessionExplain);
    }

    @Test
    void concurrentFamilyRevocationsAreIdempotentAndDoNotAffectSiblingSessions() throws Exception {
        String email = "concurrent-revoke-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        AccountResponse.Login first = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        AccountResponse.Login second = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        UUID firstFamily = UUID.fromString(SignedJWT.parse(first.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        UUID secondFamily = UUID.fromString(SignedJWT.parse(second.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = workers.submit(() -> {
                start.await();
                accountService.revokeSession(accountId, firstFamily);
                return null;
            });
            Future<?> two = workers.submit(() -> {
                start.await();
                accountService.revokeSession(accountId, firstFamily);
                return null;
            });
            start.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT revoked_at IS NOT NULL FROM refresh_token_family WHERE family_id = ?
                """, Boolean.class, firstFamily)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT revoked_at IS NULL FROM refresh_token_family WHERE family_id = ?
                """, Boolean.class, secondFamily)).isTrue();
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void expiredRefreshFamilyRejectsStillUnexpiredAccessToken() throws Exception {
        String email = "expired-family-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        AccountResponse.Login login = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        UUID familyId = UUID.fromString(SignedJWT.parse(login.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        jdbc.update("UPDATE refresh_token_family SET created_at = ?, expires_at = ? WHERE family_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().minusSeconds(1)),
                familyId);

        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + login.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionInventoryTargetedRevocationAndPasswordResetAreImmediateAcrossFamilies() throws Exception {
        String email = "sessions-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        String buyerRef = accountService.buyerRef(accountId);
        AccountResponse.Login first = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        AccountResponse.Login second = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        UUID firstFamily = UUID.fromString(SignedJWT.parse(first.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        UUID secondFamily = UUID.fromString(SignedJWT.parse(second.tokens().accessToken())
                .getJWTClaimsSet().getStringClaim("sid"));
        String sessionsPath = "/api/auth/sessions";

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + second.tokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.tokens().refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.current == true)]").exists());
        mockMvc.perform(get(sessionsPath).param("limit", "1").param("offset", "0")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
        mockMvc.perform(get(sessionsPath).param("limit", "1").param("offset", "1")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get(sessionsPath).param("limit", "101")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(sessionsPath).param("offset", "10001")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(sessionsPath + "/{familyId}", firstFamily)
                        .header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].familyId").value(secondFamily.toString()))
                .andExpect(jsonPath("$.items[0].current").value(true))
                .andExpect(jsonPath("$.items[0].refreshToken").doesNotExist())
                .andExpect(jsonPath("$.items[0].tokenHash").doesNotExist());

        String otherEmail = "foreign-sessions-" + UUID.randomUUID() + "@example.com";
        UUID otherAccountId = accounts.createAccount(otherEmail, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(otherAccountId);
        accounts.verifyEmail(otherAccountId, Instant.now());
        AccountResponse.Login other = accountService.login(new AccountRequest.Login(otherEmail, password), "198.51.100.7");
        mockMvc.perform(delete(sessionsPath + "/{familyId}", secondFamily)
                        .header("Authorization", "Bearer " + other.tokens().accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isOk());

        accountService.requestPasswordReset(email, "198.51.100.7");
        String resetToken = testEmailSender.passwordResetTokens.poll();
        assertThat(resetToken).isNotBlank();
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + resetToken
                                + "\",\"newPassword\":\"new-long-enough-password-456\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + other.tokens().accessToken()))
                .andExpect(status().isOk());

        AccountResponse.Login afterReset = accountService.login(
                new AccountRequest.Login(email, "new-long-enough-password-456"), "198.51.100.7");
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + afterReset.tokens().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        jdbc.update("UPDATE user_account SET status = 'DISABLED' WHERE account_id = ?", accountId);
        mockMvc.perform(get(sessionsPath).header("Authorization", "Bearer " + afterReset.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetLiftsTheLoginLockoutOnlyAfterTheMailboxIsProven() {
        // 비밀번호를 되찾은 사용자가 잠금 창이 끝날 때까지 새 비밀번호로도 못 들어가면 안 된다.
        // 잠금은 실패 횟수로만 걸리므로, 메일함 소유를 증명한 재설정이 끝나야만 풀려야 한다.
        String email = "lockout-reset-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        String newPassword = "new-long-enough-password-456";
        String origin = "203.0.113." + (1 + (int) (Math.random() * 200));
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, "wrong-password-123"), origin)).isInstanceOf(AccountException.class);
        }
        assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, password), origin)).isInstanceOf(AccountException.class);

        accountService.requestPasswordReset(email, origin);
        String resetToken = testEmailSender.passwordResetTokens.poll();
        assertThat(resetToken).isNotBlank();
        assertThatThrownBy(() -> accountService.resetPassword("not-a-real-token", newPassword)).isInstanceOf(AccountException.class);
        assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, password), origin)).isInstanceOf(AccountException.class);

        accountService.resetPassword(resetToken, newPassword);

        assertThat(accountService.login(new AccountRequest.Login(email, newPassword), origin).tokens()).isNotNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Login test lock timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login test interrupted", exception);
        }
    }

    /**
     * 현행 경로의 가입 메일은 transaction이 DB 연결을 돌려준 뒤에 보낸다. afterCommit 콜백에서 보내면 연결을 쥔 채
     * SMTP를 기다려, SMTP가 멈췄을 때 요청 10건으로 Hikari 풀이 고갈되고 무관한 API까지 503이 됐다(EXP-113 C2).
     */
    @Test
    void signupMailIsSentAfterTheDatabaseConnectionIsReleased() {
        String email = "release-" + UUID.randomUUID() + "@example.com";
        testEmailSender.verificationSentWithBoundResources.clear();

        accountService.registerBuyer(new AccountRequest.RegisterBuyer(email, "long-enough-password-123"),
                "198.51.100.91");

        assertThat(testEmailSender.verificationSentWithBoundResources).containsExactly(false);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEmailConfiguration {
        @Bean
        @Primary
        TestAccountEmailSender testAccountEmailSender() {
            return new TestAccountEmailSender();
        }
    }

    static class TestAccountEmailSender implements AccountEmailSender {
        private final ConcurrentLinkedQueue<String> passwordResetTokens = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Boolean> verificationSentWithBoundResources = new ConcurrentLinkedQueue<>();

        @Override
        public void sendVerification(String email, String token) {
            // 전송 시점에 이 스레드에 묶인 transaction 자원(JDBC 연결 등)이 있는지 기록한다.
            verificationSentWithBoundResources.add(!TransactionSynchronizationManager.getResourceMap().isEmpty());
        }

        @Override
        public void sendPasswordReset(String email, String token) {
            passwordResetTokens.add(token);
        }
    }


    /**
     * 계정 폐쇄가 즉시·전면적으로 적용되는지 확인합니다.
     *
     * <p>폐쇄는 되돌릴 수 없는 작업이므로 다음이 모두 성립해야 합니다:
     * 같은 액세스 토큰이 즉시 401, refresh 거부, 다른 세션도 함께 무효화,
     * 잘못된 비밀번호는 상태를 바꾸지 않음, 재요청은 멱등.
     */
    @Test
    void closingAccountInvalidatesEverySessionImmediatelyAndRequiresThePassword() throws Exception {
        String email = "closure-" + UUID.randomUUID() + "@example.com";
        String password = "long-enough-password-123";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(password)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        AccountResponse.Login first = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        AccountResponse.Login second = accountService.login(new AccountRequest.Login(email, password), "198.51.100.7");
        String closurePath = "/api/auth/account";

        // 잘못된 비밀번호는 계정을 건드리지 않는다.
        mockMvc.perform(delete(closurePath)
                        .header("Authorization", "Bearer " + first.tokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-but-long-enough-123\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isOk());

        // 올바른 비밀번호로 폐쇄하면 204.
        mockMvc.perform(delete(closurePath)
                        .header("Authorization", "Bearer " + first.tokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\"}"))
                .andExpect(status().isNoContent());

        // 같은 액세스 토큰이 즉시 거부된다.
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + first.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        // 다른 기기의 세션도 함께 거부된다.
        mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + second.tokens().accessToken()))
                .andExpect(status().isUnauthorized());
        // refresh 도 거부된다.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.tokens().refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        // 로그인도 불가능하다.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
        // 이미 폐쇄된 계정에 다시 요청하면 토큰이 이미 무효라 401 이다(멱등).
        mockMvc.perform(delete(closurePath)
                        .header("Authorization", "Bearer " + first.tokens().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
