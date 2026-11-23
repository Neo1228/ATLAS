package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.account.dto.request.LoginRequest;
import com.atlas.account.exception.AccountException;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.repository.AuthThrottleRepository;
import com.atlas.account.service.AccountService;
import com.atlas.account.service.AuthAttemptLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 인증 시도 제한을 실제 PostgreSQL에서 검증한다.
 *
 * <p>핵심 계약: 한도를 넘으면 <b>비밀번호 검증이 실행되지 않는다.</b> 그래야 PBKDF2 CPU 비용을
 * 공격자가 소비시키지 못한다. 그리고 응답은 실제 인증 실패와 구분되지 않아야 한다.
 */
@Testcontainers
@SpringBootTest
class AuthAttemptLimiterIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static final String ORIGIN = "198.51.100.42";
    private static final String PASSWORD = "long-enough-password-123";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.auth.throttle.window", () -> "PT15M");
        properties.add("atlas.auth.throttle.subject-limit", () -> "3");
        properties.add("atlas.auth.throttle.origin-limit", () -> "100");
    }

    @Autowired private AccountRepository accounts;
    @Autowired private AccountService accountService;
    @Autowired private AuthAttemptLimiter limiter;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void repeatedWrongPasswordsLockTheSubjectAndStopPasswordHashing() {
        final String email = verifiedAccount();

        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password-1"), ORIGIN))
                .isInstanceOf(AccountException.class);
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password-2"), ORIGIN))
                .isInstanceOf(AccountException.class);
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password-3"), ORIGIN))
                .isInstanceOf(AccountException.class);

        assertThat(failures("LOGIN", email)).isEqualTo(3);

        // 한도를 넘은 뒤에는 올바른 비밀번호도 거절된다. 검증 전에 막히므로 CPU를 쓰지 않는다.
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, PASSWORD), ORIGIN))
                .isInstanceOf(AccountException.class)
                .hasFieldOrPropertyWithValue("code", AccountException.Code.INVALID_CREDENTIALS);
        long elapsedNanos = System.nanoTime() - startedAt;

        // 실제 PBKDF2 검증 2회(약 100 ms)보다 확실히 짧아야 한다. 로컬 측정 기준의 느슨한 상한이다.
        assertThat(elapsedNanos).isLessThan(Duration.ofMillis(60).toNanos());
        assertThat(failures("LOGIN", email)).isEqualTo(3);
    }

    @Test
    void successfulLoginClearsTheFailureCounter() {
        String email = verifiedAccount();

        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password"), ORIGIN))
                .isInstanceOf(AccountException.class);
        assertThat(failures("LOGIN", email)).isEqualTo(1);

        accountService.login(new LoginRequest(email, PASSWORD), ORIGIN);

        assertThat(failures("LOGIN", email)).isZero();
        // 초기화 후에는 다시 정상 로그인이 가능하다.
        assertThat(accountService.login(new LoginRequest(email, PASSWORD), ORIGIN).mfaRequired()).isFalse();
    }

    @Test
    void expiredWindowReleasesTheSubjectWithoutOperatorAction() {
        String email = verifiedAccount();
        for (int attempt = 1; attempt <= 3; attempt++) {
            String wrongPassword = "wrong-password-" + attempt;
            assertThatThrownBy(() -> accountService.login(new LoginRequest(email, wrongPassword), ORIGIN))
                    .isInstanceOf(AccountException.class);
        }
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, PASSWORD), ORIGIN))
                .isInstanceOf(AccountException.class);

        // 창이 지난 상태를 재현한다. 잠금은 시간이 지나면 스스로 풀려야 한다.
        jdbc.update("""
                UPDATE auth_subject_bucket SET window_started_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                WHERE scope = 'LOGIN'
                """);

        assertThat(accountService.login(new LoginRequest(email, PASSWORD), ORIGIN).mfaRequired()).isFalse();
    }

    @Test
    void countersAreKeyedByDigestAndNeverStoreTheRawEmailOrAddress() {
        String email = verifiedAccount();
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password"), ORIGIN))
                .isInstanceOf(AccountException.class);

        var rows = jdbc.queryForList("""
                SELECT subject_key, origin_key FROM auth_subject_bucket s
                LEFT JOIN auth_origin_bucket o ON TRUE
                """);
        assertThat(rows).isNotEmpty();
        for (var row : rows) {
            assertThat(String.valueOf(row.get("subject_key"))).doesNotContain(email);
            assertThat(String.valueOf(row.get("origin_key"))).doesNotContain(ORIGIN);
        }
    }

    @Test
    void originLimitIsSharedAcrossSubjectsSoOneSourceCannotSprayManyAccounts() {
        AuthAttemptLimiter strict = new AuthAttemptLimiter(new AuthThrottleRepository(jdbc), transactionManager, Duration.ofMinutes(15), 100, 2);
        Instant now = Instant.now();
        String origin = "203.0.113." + UUID.randomUUID().toString().substring(0, 8);

        assertThat(strict.tryAcquire("LOGIN", "a@example.com", origin, now)).isTrue();
        assertThat(strict.tryAcquire("LOGIN", "b@example.com", origin, now)).isTrue();
        assertThat(strict.tryAcquire("LOGIN", "c@example.com", origin, now)).isFalse();
        // 다른 출처는 영향받지 않는다.
        assertThat(strict.tryAcquire("LOGIN", "d@example.com", "203.0.113.250", now)).isTrue();
    }

    @Test
    void mailRequestsStopSendingOnceTheOriginBudgetIsSpent() {
        AuthAttemptLimiter strict = new AuthAttemptLimiter(new AuthThrottleRepository(jdbc), transactionManager, Duration.ofMinutes(15), 100, 1);
        Instant now = Instant.now();
        String origin = "192.0.2." + UUID.randomUUID().toString().substring(0, 8);

        assertThat(strict.tryAcquire("EMAIL", "one@example.com", origin, now)).isTrue();
        assertThat(strict.tryAcquire("EMAIL", "two@example.com", origin, now)).isFalse();
    }

    @Test
    void staleCountersArePurgedSoRowCountFollowsActiveSubjects() {
        String email = verifiedAccount();
        assertThatThrownBy(() -> accountService.login(new LoginRequest(email, "wrong-password"), ORIGIN))
                .isInstanceOf(AccountException.class);
        assertThat(failures("LOGIN", email)).isEqualTo(1);

        int removed = limiter.purgeStale(Instant.now().plus(Duration.ofDays(1)), Duration.ofHours(1), 1000);

        assertThat(removed).isPositive();
        assertThat(failures("LOGIN", email)).isZero();
    }

    private int failures(String scope, String email) {
        return jdbc.query("""
                SELECT failures FROM auth_subject_bucket WHERE subject_key = ? AND scope = ?
                """, (row, number) -> row.getInt(1),
                AuthAttemptLimiter.key(email.toLowerCase()), scope).stream().findFirst().orElse(0);
    }

    private String verifiedAccount() {
        String email = "throttle-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(PASSWORD)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, Instant.now());
        return email;
    }
}
