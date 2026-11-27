package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.dto.response.AccountResponse;
import com.atlas.account.exception.AccountException;
import com.atlas.account.exception.AccountException.Code;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountEmailSender;
import com.atlas.account.service.AccountService;
import com.bastiaanjansen.otp.TOTPGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FR-018·INV-015의 시간 경계, 동시 경합, 단회 소비를 실제 PostgreSQL과 주입한 시계로 증명한다
 * (ADR-0093, EXP-101). 시간은 모두 시험이 {@link AdjustableClock}에 지정한 값이고 경계는 직전과 정각을 쌍으로 확인한다.
 * 모의 저장소 단위 테스트는 SQL의 {@code expires_at > ?}나 행 잠금이 실제로 그 순간을 지키는지 증명하지 못한다.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
@Import(AccountCredentialLifecycleIntegrationTest.TestConfig.class)
class AccountCredentialLifecycleIntegrationTest {
    /** 동시 refresh 경합을 반복하는 횟수. 결과 집합은 행 잠금과 조건부 갱신이 정하므로 작은 횟수로 충분하다. */
    private static final int ROUNDS = 30;
    private static final String PASSWORD = "long-enough-password-123";
    private static final String NEW_PASSWORD = "new-long-enough-password-456";
    private static final AtomicInteger ORIGIN_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withLabel("atlasexp", "t1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.account.mfa.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        // 요청 직후 받은 토큰을 같은 스레드에서 읽는다. 되돌림 경로(outbox 꺼짐)를 고정한다.
        registry.add("atlas.account.notification.outbox.enabled", () -> "false");
    }

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private AdjustableClock clock;
    @Autowired private CapturingEmailSender emails;

    @Test
    void refreshTokenAndFamilyExpireExactlyAtTheirBoundaries() throws Exception {
        Instant start = startAt();
        String email = newVerifiedAccount();

        // 7일: refresh token은 발급 7일 직전까지 쓰이고 정각에는 거절된다.
        AccountResponse.Login sevenDay = login(email, PASSWORD);
        AccountResponse.Login sevenDayEdge = login(email, PASSWORD);
        clock.set(start.plus(Duration.ofDays(7)).minusSeconds(1));
        AccountResponse.Token justBefore = accountService.refresh(
                new AccountRequest.RefreshToken(sevenDay.tokens().refreshToken()));
        assertThat(justBefore.refreshToken()).isNotEqualTo(sevenDay.tokens().refreshToken());
        clock.set(start.plus(Duration.ofDays(7)));
        assertRefreshRejected(sevenDayEdge.tokens().refreshToken(), Code.INVALID_REFRESH_TOKEN);

        // 30일: family는 정각에 끝나고, 새 refresh token은 어느 시점에 발급해도 family 만료를 넘지 않는다.
        clock.set(start);
        AccountResponse.Token current = login(email, PASSWORD).tokens();
        UUID familyId = familyOf(current.accessToken());
        Instant familyExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM refresh_token_family WHERE family_id = ?", Timestamp.class, familyId).toInstant();
        assertThat(familyExpiry).isEqualTo(start.plus(Duration.ofDays(30)));
        for (int day : new int[] {6, 12, 18, 24}) {
            clock.set(start.plus(Duration.ofDays(day)));
            current = accountService.refresh(new AccountRequest.RefreshToken(current.refreshToken()));
        }
        assertThat(liveRefreshExpiry(familyId)).as("24일째 발급한 token은 31일이 아니라 family 만료(30일)에서 끝난다")
                .isEqualTo(familyExpiry);
        clock.set(familyExpiry.minusSeconds(1));
        current = accountService.refresh(new AccountRequest.RefreshToken(current.refreshToken()));
        assertThat(liveRefreshExpiry(familyId)).isEqualTo(familyExpiry);
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + current.accessToken()))
                .andExpect(status().isOk());

        clock.set(familyExpiry);
        assertRefreshRejected(current.refreshToken(), Code.INVALID_REFRESH_TOKEN);
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + current.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + current.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reusedRefreshTokenRevokesTheLatestTokenAndLeavesSiblingSessionsAlone() throws Exception {
        startAt();
        String email = newVerifiedAccount();
        AccountResponse.Token first = login(email, PASSWORD).tokens();
        AccountResponse.Token sibling = login(email, PASSWORD).tokens();

        AccountResponse.Token rotated = accountService.refresh(new AccountRequest.RefreshToken(first.refreshToken()));
        assertRefreshRejected(first.refreshToken(), Code.REFRESH_TOKEN_REUSED);
        // 재사용이 감지되면 가장 최근에 발급된 refresh token도 막힌다.
        assertRefreshRejected(rotated.refreshToken(), Code.INVALID_REFRESH_TOKEN);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM account_refresh_token
                WHERE family_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, Integer.class, familyOf(first.accessToken()))).isZero();
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + rotated.accessToken()))
                .andExpect(status().isUnauthorized());

        // 다른 세션(family)은 영향을 받지 않는다.
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + sibling.accessToken()))
                .andExpect(status().isOk());
        assertThat(accountService.refresh(new AccountRequest.RefreshToken(sibling.refreshToken())).refreshToken())
                .isNotBlank();

        // 저장하는 것은 token 원문이 아니라 SHA-256 hash뿐이다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_refresh_token WHERE token_hash = ?",
                Integer.class, first.refreshToken())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_refresh_token WHERE token_hash = ?",
                Integer.class, sha256(first.refreshToken()))).isEqualTo(1);
    }

    @Test
    void concurrentRefreshOfOneTokenSucceedsOnceAndRevokesTheWholeFamily() throws Exception {
        startAt();
        String email = newVerifiedAccount();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                AccountResponse.Token token = login(email, PASSWORD).tokens();
                CountDownLatch go = new CountDownLatch(1);
                Callable<RefreshOutcome> attempt = () -> {
                    go.await();
                    try {
                        return new RefreshOutcome(accountService.refresh(
                                new AccountRequest.RefreshToken(token.refreshToken())), null);
                    } catch (AccountException exception) {
                        return new RefreshOutcome(null, exception.code());
                    }
                };
                Future<RefreshOutcome> one = pool.submit(attempt);
                Future<RefreshOutcome> two = pool.submit(attempt);
                go.countDown();
                List<RefreshOutcome> outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));

                List<RefreshOutcome> winners = outcomes.stream().filter(o -> o.token() != null).toList();
                List<RefreshOutcome> losers = outcomes.stream().filter(o -> o.token() == null).toList();
                assertThat(winners).as("같은 token의 동시 사용은 정확히 한 건만 성공한다(round %d)", round).hasSize(1);
                assertThat(losers).extracting(RefreshOutcome::code).containsExactly(Code.REFRESH_TOKEN_REUSED);
                // 성공한 쪽이 받은 token도, 그 access token도 이미 폐기됐다.
                AccountResponse.Token won = winners.get(0).token();
                assertRefreshRejected(won.refreshToken(), Code.INVALID_REFRESH_TOKEN);
                assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_token_family WHERE family_id = ?",
                        Boolean.class, familyOf(token.accessToken()))).isTrue();
                mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + won.accessToken()))
                        .andExpect(status().isUnauthorized());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emailVerificationTokenExpiresAtTwentyFourHoursAndLoginStaysBlockedUntilItIsConsumed() throws Exception {
        Instant start = startAt();
        String email = "verify-" + UUID.randomUUID() + "@example.com";
        String origin = newOrigin();

        accountService.registerBuyer(new AccountRequest.RegisterBuyer(email, PASSWORD), origin);
        String expired = emails.verification(email);
        // 인증 전에는 올바른 비밀번호로도 로그인할 수 없다.
        assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, PASSWORD), origin))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_CREDENTIALS));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        clock.set(start.plus(Duration.ofHours(24)));
        assertThatThrownBy(() -> accountService.verifyEmail(expired))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_ACTION_TOKEN));
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM user_account WHERE email = ?",
                Boolean.class, email)).isTrue();
        assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, PASSWORD), origin))
                .isInstanceOf(AccountException.class);

        // 재요청한 새 token은 새 24시간을 얻고, 이전 token은 대체된다.
        accountService.requestEmailVerification(email, origin);
        String fresh = emails.verification(email);
        assertThat(fresh).isNotEqualTo(expired);
        clock.set(start.plus(Duration.ofHours(48)).minusSeconds(1));
        accountService.verifyEmail(fresh);
        assertThat(accountService.login(new AccountRequest.Login(email, PASSWORD), origin).tokens()).isNotNull();

        // 단회 소비이고, 저장하는 것은 SHA-256 hash뿐이다.
        assertThatThrownBy(() -> accountService.verifyEmail(fresh))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_ACTION_TOKEN));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_action_token WHERE token_hash = ?",
                Integer.class, fresh)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_action_token WHERE token_hash = ? AND consumed_at IS NOT NULL",
                Integer.class, sha256(fresh))).isEqualTo(1);
    }

    @Test
    void passwordResetTokenExpiresAtThirtyMinutesAndSuccessRevokesEverySession() throws Exception {
        Instant start = startAt();
        String email = newVerifiedAccount();
        String origin = newOrigin();
        AccountResponse.Token session = login(email, PASSWORD).tokens();
        String passwordHash = accounts.findPasswordHash(email).orElseThrow();

        accountService.requestPasswordReset(email, origin);
        String expired = emails.reset(email);
        clock.set(start.plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> accountService.resetPassword(expired, NEW_PASSWORD))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_ACTION_TOKEN));
        // 거절된 재설정은 비밀번호도 세션도 바꾸지 않는다.
        assertThat(accounts.findPasswordHash(email)).contains(passwordHash);
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());

        accountService.requestPasswordReset(email, origin);
        String fresh = emails.reset(email);
        assertThat(fresh).isNotEqualTo(expired);
        AccountResponse.Token beforeReset = login(email, PASSWORD).tokens();
        clock.set(start.plus(Duration.ofMinutes(60)).minusSeconds(1));
        accountService.resetPassword(fresh, NEW_PASSWORD);

        assertThat(accounts.findPasswordHash(email)).isNotEqualTo(Optional.of(passwordHash));
        assertRefreshRejected(session.refreshToken(), Code.INVALID_REFRESH_TOKEN);
        assertRefreshRejected(beforeReset.refreshToken(), Code.INVALID_REFRESH_TOKEN);
        mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + beforeReset.accessToken()))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> accountService.login(new AccountRequest.Login(email, PASSWORD), origin))
                .isInstanceOf(AccountException.class);
        assertThat(accountService.login(new AccountRequest.Login(email, NEW_PASSWORD), origin).tokens()).isNotNull();

        // 단회 소비이고, 저장하는 것은 SHA-256 hash뿐이다.
        assertThatThrownBy(() -> accountService.resetPassword(fresh, "another-long-enough-password-789"))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_ACTION_TOKEN));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_action_token WHERE token_hash = ?",
                Integer.class, fresh)).isZero();
    }

    @Test
    void passwordResetMakesAnOutstandingMfaChallengeUnusable() throws Exception {
        Instant start = startAt();
        String email = newVerifiedAccount();
        String origin = newOrigin();
        MfaSetup mfa = enableMfa(email);
        clock.set(start.plusSeconds(120));
        String challenge = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        accountService.requestPasswordReset(email, origin);

        accountService.resetPassword(emails.reset(email), NEW_PASSWORD);

        String code = totp(mfa.secret());
        assertThatThrownBy(() -> accountService.completeMfaLogin(new AccountRequest.MfaChallenge(challenge, code, null)))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_CREDENTIALS));
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM account_mfa_challenge WHERE challenge_hash = ?",
                Boolean.class, sha256(challenge))).isTrue();
        // 양성 대조군: 같은 코드는 새 비밀번호로 새로 받은 challenge에서 통과한다.
        String fresh = accountService.login(new AccountRequest.Login(email, NEW_PASSWORD), origin).challengeToken();
        assertThat(accountService.completeMfaLogin(new AccountRequest.MfaChallenge(fresh, code, null)).accessToken())
                .isNotBlank();
    }

    @Test
    void mfaChallengeExpiresAtFiveMinutes() throws Exception {
        Instant start = startAt();
        String email = newVerifiedAccount();
        String origin = newOrigin();
        MfaSetup mfa = enableMfa(email);

        Instant issuedAt = start.plus(Duration.ofMinutes(10));
        clock.set(issuedAt);
        String expired = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        clock.set(issuedAt.plus(Duration.ofMinutes(5)));
        String lateCode = totp(mfa.secret());
        assertThatThrownBy(() -> accountService.completeMfaLogin(new AccountRequest.MfaChallenge(expired, lateCode, null)))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_CREDENTIALS));

        // 정각 직전의 challenge는 올바른 코드로 통과한다.
        String live = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        clock.set(clock.instant().plus(Duration.ofMinutes(5)).minusSeconds(1));
        assertThat(accountService.completeMfaLogin(
                new AccountRequest.MfaChallenge(live, totp(mfa.secret()), null)).accessToken()).isNotBlank();
    }

    @Test
    void totpStepAndRecoveryCodeCanBeUsedOnlyOnce() throws Exception {
        Instant start = startAt();
        String email = newVerifiedAccount();
        String origin = newOrigin();
        MfaSetup mfa = enableMfa(email);
        List<String> recovery = mfa.recoveryCodes();
        assertThat(recovery).hasSize(10);
        // TOTP secret은 평문이 아니라 암호문으로 저장된다.
        String stored = jdbc.queryForObject("""
                SELECT encrypted_secret FROM account_mfa_secret
                WHERE account_id = (SELECT account_id FROM user_account WHERE email = ?)
                """, String.class, email);
        assertThat(stored).isNotBlank().doesNotContain(mfa.secret());

        clock.set(start.plus(Duration.ofMinutes(1)));
        String code = totp(mfa.secret());
        String first = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        assertThat(accountService.completeMfaLogin(new AccountRequest.MfaChallenge(first, code, null)).accessToken())
                .isNotBlank();

        // 같은 time step의 코드는 다른 challenge에서도 다시 쓸 수 없다.
        String second = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        assertThatThrownBy(() -> accountService.completeMfaLogin(new AccountRequest.MfaChallenge(second, code, null)))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_CREDENTIALS));

        // recovery code는 한 번만 쓰이고, 쓰인 코드는 새 challenge에서도 거절된다.
        String used = recovery.get(0);
        assertThat(accountService.completeMfaLogin(new AccountRequest.MfaChallenge(second, null, used)).accessToken())
                .isNotBlank();
        String third = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        assertThatThrownBy(() -> accountService.completeMfaLogin(new AccountRequest.MfaChallenge(third, null, used)))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_CREDENTIALS));
        assertThat(accountService.completeMfaLogin(
                new AccountRequest.MfaChallenge(third, null, recovery.get(1))).accessToken()).isNotBlank();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM account_mfa_recovery_code
                WHERE account_id = (SELECT account_id FROM user_account WHERE email = ?) AND consumed_at IS NOT NULL
                """, Integer.class, email)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_mfa_recovery_code WHERE code_hash = ?",
                Integer.class, used)).isZero();

        // 양성 대조군: 다음 time step의 코드는 통과한다(step 단위 소비이지 계정 잠금이 아니다).
        clock.set(clock.instant().plusSeconds(30));
        String fourth = accountService.login(new AccountRequest.Login(email, PASSWORD), origin).challengeToken();
        assertThat(accountService.completeMfaLogin(
                new AccountRequest.MfaChallenge(fourth, totp(mfa.secret()), null)).accessToken()).isNotBlank();
    }

    @Test
    void signupVerificationAndResetRequestsAnswerTheSameForKnownAndUnknownEmails() throws Exception {
        startAt();
        String known = newVerifiedAccount();
        String unverified = "unverified-" + UUID.randomUUID() + "@example.com";
        accountService.registerBuyer(new AccountRequest.RegisterBuyer(unverified, PASSWORD), newOrigin());
        String unknown = "unknown-" + UUID.randomUUID() + "@example.com";

        assertSameAnswer("/api/auth/signup", Map.of(
                "known", "{\"email\":\"" + known + "\",\"password\":\"" + PASSWORD + "\"}",
                "unknown", "{\"email\":\"" + unknown + "\",\"password\":\"" + PASSWORD + "\"}"));
        assertSameAnswer("/api/auth/password-reset/request", Map.of(
                "known", "{\"email\":\"" + known + "\"}",
                "unknown", "{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\"}"));
        assertSameAnswer("/api/auth/email-verification/request", Map.of(
                "known", "{\"email\":\"" + unverified + "\"}",
                "unknown", "{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\"}"));
    }

    // ---- 시험 보조 ----

    /** 같은 요청 범위의 알려진 이메일과 없는 이메일이 status와 body를 한 글자도 다르지 않게 받는지 확인한다. */
    private void assertSameAnswer(String path, Map<String, String> bodies) throws Exception {
        List<String> answers = new ArrayList<>();
        for (String body : List.of(bodies.get("known"), bodies.get("unknown"))) {
            var result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(request -> {
                                request.setRemoteAddr(newOrigin());
                                return request;
                            }))
                    .andReturn().getResponse();
            answers.add(result.getStatus() + " " + result.getContentAsString());
        }
        assertThat(answers.get(0)).as(path).isEqualTo(answers.get(1));
        assertThat(answers.get(0)).startsWith("2");
    }

    private Instant startAt() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(start);
        return start;
    }

    private static String newOrigin() {
        int n = ORIGIN_SEQUENCE.incrementAndGet();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    private String newVerifiedAccount() {
        String email = "life-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(PASSWORD)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, clock.instant());
        return email;
    }

    private AccountResponse.Login login(String email, String password) {
        return accountService.login(new AccountRequest.Login(email, password), newOrigin());
    }

    private MfaSetup enableMfa(String email) {
        UUID accountId = jdbc.queryForObject("SELECT account_id FROM user_account WHERE email = ?", UUID.class, email);
        String secret = accountService.startMfaEnrollment(accountId).secret();
        List<String> recoveryCodes = accountService.confirmMfaEnrollment(accountId, totp(secret)).recoveryCodes();
        return new MfaSetup(secret, recoveryCodes);
    }

    private String totp(String secret) {
        return new TOTPGenerator.Builder(secret).withClock(clock).build().now();
    }

    private void assertRefreshRejected(String refreshToken, Code expected) {
        assertThatThrownBy(() -> accountService.refresh(new AccountRequest.RefreshToken(refreshToken)))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    private Instant liveRefreshExpiry(UUID familyId) {
        return jdbc.queryForObject("""
                SELECT expires_at FROM account_refresh_token
                WHERE family_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """, Timestamp.class, familyId).toInstant();
    }

    private static UUID familyOf(String accessToken) throws Exception {
        return UUID.fromString(SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim("sid"));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record MfaSetup(String secret, List<String> recoveryCodes) { }

    private record RefreshOutcome(AccountResponse.Token token, Code code) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }

        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());

        void set(Instant instant) {
            current.set(instant);
        }

        @Override public Instant instant() {
            return current.get();
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 보낸 메일 대신 token을 받는다. 같은 주소로 여러 번 요청하면 가장 최근 token을 돌려준다. */
    static final class CapturingEmailSender implements AccountEmailSender {
        private final Map<String, Deque<String>> verification = new ConcurrentHashMap<>();
        private final Map<String, Deque<String>> reset = new ConcurrentHashMap<>();

        @Override
        public void sendVerification(String email, String token) {
            verification.computeIfAbsent(email, key -> new ArrayDeque<>()).push(token);
        }

        @Override
        public void sendPasswordReset(String email, String token) {
            reset.computeIfAbsent(email, key -> new ArrayDeque<>()).push(token);
        }

        String verification(String email) {
            return verification.get(email).peek();
        }

        String reset(String email) {
            return reset.get(email).peek();
        }
    }
}
