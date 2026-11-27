package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.repository.AccountNotificationRepository;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountEmailSender;
import com.atlas.account.service.AccountNotificationService;
import com.atlas.account.service.AccountService;
import com.atlas.application.service.AccountNotificationWorker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 인증 메일 outbox(V9, ADR-0102)가 커밋된 요청을 잃지 않음을 실제 PostgreSQL로 확인한다(EXP-113 H3·H7).
 *
 * <p>현행 경로는 커밋 뒤 메모리에서만 보내므로 그 사이에 프로세스가 죽으면 메일이 사라진다. 여기서는 요청 경로가
 * 아무것도 보내지 않고 커밋만 한 상태(프로세스가 그 직후 죽은 것과 같은 DB 상태)와, worker가 임대를 잡은 채 죽은
 * 상태에서 시작해 새 worker 주기가 메일을 보내는지 본다. 주기 실행은 끄고 시험이 직접 호출한다.
 */
@Testcontainers
@SpringBootTest
@Import(AccountNotificationOutboxIntegrationTest.TestConfig.class)
class AccountNotificationOutboxIntegrationTest {
    private static final String PASSWORD = "long-enough-password-123";
    private static final String NEW_PASSWORD = "new-long-enough-password-456";
    private static final Duration LEASE = Duration.ofMinutes(2);
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
        // outbox.enabled는 일부러 지정하지 않는다. 기본값(application.yml, true)이 outbox 경로임을 이 시험이 지킨다.
        registry.add("atlas.account.notification.worker.initial-delay", () -> "PT24H");
        registry.add("atlas.account.notification.lease-duration", () -> LEASE.toString());
        registry.add("atlas.account.notification.retry.initial-backoff", () -> "PT5S");
        registry.add("atlas.account.notification.retry.max-attempts", () -> "2");
    }

    @Autowired private AccountService accountService;
    @Autowired private AccountNotificationService notifications;
    @Autowired private AccountNotificationRepository outbox;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdjustableClock clock;
    @Autowired private RecordingEmailSender emails;
    @Autowired private ObjectProvider<AccountNotificationWorker> worker;

    @Test
    void outboxAndItsWorkerAreOnWithoutAnyFlag() {
        // 플래그를 지정하지 않은 문맥이다. 기본값이 꺼지면 요청은 바로 보내고 worker는 등록되지 않는다.
        assertThat(worker.getIfAvailable()).as("기본 문맥에 outbox worker가 있어야 한다").isNotNull();
    }

    @Test
    void committedSignupIsDeliveredAfterProcessDeathAndAfterWorkerDiesHoldingTheLease() {
        Instant start = startAt();
        String email = "outbox-" + UUID.randomUUID() + "@example.com";
        accountService.registerBuyer(new AccountRequest.RegisterBuyer(email, PASSWORD), newOrigin());
        UUID accountId = accountIdOf(email);

        // 요청은 커밋만 하고 보내지 않는다. 프로세스가 여기서 죽어도 전달 의도는 계정과 같은 transaction에 남아 있다.
        assertThat(emails.sent(email)).isEmpty();
        assertThat(outboxRows(accountId)).isEqualTo(1);
        assertThat(issuedTokens(accountId)).as("토큰은 outbox에도 요청 시점에도 만들지 않는다").isZero();

        // 재기동한 worker가 임대를 잡은 채 다시 죽는다. 임대가 끝나기 전에는 다른 worker가 잡지 않는다.
        assertThat(outbox.claimNext(UUID.randomUUID(), start, start.plus(LEASE))).isPresent();
        notifications.deliverDue(50);
        assertThat(emails.sent(email)).isEmpty();

        clock.set(start.plus(LEASE).plusSeconds(1));
        notifications.deliverDue(50);

        assertThat(emails.sent(email)).as("커밋된 가입 메일은 정확히 한 통 전달된다").hasSize(1);
        assertThat(outboxRows(accountId)).isZero();
        accountService.verifyEmail(emails.latest(email));
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_account WHERE account_id = ?",
                Boolean.class, accountId)).isTrue();
    }

    @Test
    void smtpFailureIsRetriedAfterBackoffThenParkedUntilTheUserAsksAgain() {
        Instant start = startAt();
        String email = newVerifiedAccount();
        UUID accountId = accountIdOf(email);
        emails.failNext(email, 2);
        accountService.requestPasswordReset(email, newOrigin());

        notifications.deliverDue(50);
        assertThat(attempts(accountId)).isEqualTo(1);
        assertThat(status(accountId)).isEqualTo("PENDING");

        clock.set(start.plusSeconds(4));
        notifications.deliverDue(50);
        assertThat(attempts(accountId)).as("backoff 5초 전에는 다시 시도하지 않는다").isEqualTo(1);

        clock.set(start.plusSeconds(5));
        notifications.deliverDue(50);
        assertThat(status(accountId)).as("최대 2회 실패 뒤 FAILED로 남는다").isEqualTo("FAILED");
        assertThat(notifications.backlog().failed()).isPositive();

        clock.set(start.plus(Duration.ofHours(1)));
        notifications.deliverDue(50);
        assertThat(emails.sent(email)).isEmpty();

        // 사용자가 다시 요청하면 같은 행이 대기로 돌아가 이번에는 전달된다.
        accountService.requestPasswordReset(email, newOrigin());
        notifications.deliverDue(50);
        assertThat(emails.sent(email)).hasSize(1);
        assertThat(outboxRows(accountId)).isZero();
        accountService.resetPassword(emails.latest(email), NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, jdbc.queryForObject(
                "SELECT password_hash FROM user_account WHERE account_id = ?", String.class, accountId))).isTrue();
    }

    private Instant startAt() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofDays(1));
        clock.set(start);
        return start;
    }

    private String newVerifiedAccount() {
        String email = "outbox-" + UUID.randomUUID() + "@example.com";
        UUID accountId = accounts.createAccount(email, passwordEncoder.encode(PASSWORD)).orElseThrow();
        accounts.createBuyer(accountId);
        accounts.verifyEmail(accountId, clock.instant());
        return email;
    }

    private UUID accountIdOf(String email) {
        return jdbc.queryForObject("SELECT account_id FROM user_account WHERE email = ?", UUID.class, email);
    }

    private int outboxRows(UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM account_notification_outbox WHERE account_id = ?",
                Integer.class, accountId);
    }

    private int issuedTokens(UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM account_action_token WHERE account_id = ? AND purpose = ?",
                Integer.class, accountId, AccountActionTokenPurpose.EMAIL_VERIFICATION.name());
    }

    private int attempts(UUID accountId) {
        return jdbc.queryForObject("SELECT attempts FROM account_notification_outbox WHERE account_id = ?",
                Integer.class, accountId);
    }

    private String status(UUID accountId) {
        return jdbc.queryForObject("SELECT status FROM account_notification_outbox WHERE account_id = ?",
                String.class, accountId);
    }

    private static String newOrigin() {
        int n = ORIGIN_SEQUENCE.incrementAndGet();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
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

    /** SMTP 대신 받은 token을 주소별로 기록한다. {@link #failNext}로 주소별 전송 실패를 주입한다. */
    static final class RecordingEmailSender implements AccountEmailSender {
        private final Map<String, List<String>> sent = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

        @Override
        public void sendVerification(String email, String token) {
            record(email, token);
        }

        @Override
        public void sendPasswordReset(String email, String token) {
            record(email, token);
        }

        void failNext(String email, int times) {
            failures.put(email, new AtomicInteger(times));
        }

        List<String> sent(String email) {
            return sent.getOrDefault(email, List.of());
        }

        String latest(String email) {
            List<String> tokens = sent(email);
            return tokens.get(tokens.size() - 1);
        }

        private void record(String email, String token) {
            AtomicInteger remaining = failures.get(email);
            if (remaining != null && remaining.getAndDecrement() > 0) {
                throw new IllegalStateException("injected SMTP 451");
            }
            sent.computeIfAbsent(email, key -> new ArrayList<>()).add(token);
        }
    }
}
