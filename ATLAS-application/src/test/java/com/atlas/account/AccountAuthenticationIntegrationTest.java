package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.account.repository.AccountRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Testcontainers
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Login test lock timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login test interrupted", exception);
        }
    }
}
