package com.atlas.account.service;

import com.atlas.account.domain.AccountException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 인증 시도 빈도를 제한한다.
 *
 * <p>두 축을 쓴다. 주체(정규화 이메일) 축은 한 계정에 대한 비밀번호 추측을 막고, 출처(클라이언트
 * 주소) 축은 한 출처가 여러 계정을 훑거나 메일을 대량 발송하는 것을 막는다.
 *
 * <p>거절은 반드시 <b>비밀번호 검증 전에</b> 일어나야 한다. PBKDF2 검증 1회가 수십 ms의 CPU를
 * 쓰므로, 검증 뒤에 막으면 자원 고갈을 그대로 허용하게 된다.
 *
 * <p>카운터는 고정 시간 창으로 나눈 고정 크기 row를 재사용한다. 창이 바뀌면 같은 row를 새 창으로
 * 덮어쓰므로 (주체 수 × 창 수)로 무한히 늘지 않는다.
 */
@Service
public class AuthAttemptLimiter {
    /** Windows and limits are settings so operators can tune them from measured failure rates. */
    public record Policy(Duration window, int subjectLimit, int originLimit) {
        public Policy {
            Objects.requireNonNull(window);
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("throttle window must be positive");
            }
            if (subjectLimit < 1 || originLimit < 1) {
                throw new IllegalArgumentException("throttle limits must be positive");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Policy policy;

    public AuthAttemptLimiter(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${atlas.auth.throttle.window:PT15M}") Duration window,
            @Value("${atlas.auth.throttle.subject-limit:10}") int subjectLimit,
            @Value("${atlas.auth.throttle.origin-limit:60}") int originLimit) {
        this.jdbc = Objects.requireNonNull(jdbc);
        // 실패 카운터는 인증이 실패로 끝나 rollback되는 transaction과 별개로 남아야 한다.
        // 호출 트랜잭션이 있으면 그 안에서 rollback되므로 REQUIRES_NEW로 분리한다.
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = template;
        this.policy = new Policy(window, subjectLimit, originLimit);
    }

    public Policy policy() {
        return policy;
    }

    /**
     * 주체·출처 한도가 모두 남았는지 확인하고, 남았다면 출처 시도를 1 소비한다.
     *
     * <p>주체 실패는 인증이 실패한 뒤에만 기록해야 하므로 여기서 소비하지 않는다.
     */
    public boolean tryAcquire(String scope, String subject, String origin, Instant now) {
        if (isOverSubjectLimit(scope, subject, now)) return false;
        // 소비도 독립 transaction이어야 한다. 인증이 실패해 바깥 transaction이 rollback돼도
        // 시도 횟수는 남아야 한도가 실제로 줄어든다.
        Boolean allowed = transactions.execute(status -> consumeOrigin(originScope(scope), origin, now));
        return Boolean.TRUE.equals(allowed);
    }

    /** 주체 실패를 1 기록한다. 성공한 인증은 {@link #resetSubject}로 되돌린다. */
    public void recordSubjectFailure(String scope, String subject, Instant now) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO auth_subject_bucket (subject_key, scope, window_started_at, failures, updated_at)
                VALUES (?, ?, CAST(? AS timestamptz), 1, CAST(? AS timestamptz))
                ON CONFLICT (subject_key, scope) DO UPDATE SET
                    failures = CASE
                        WHEN auth_subject_bucket.window_started_at <= EXCLUDED.window_started_at
                                     - (CAST(? AS bigint) * INTERVAL '1 millisecond')
                        THEN 1 ELSE auth_subject_bucket.failures + 1 END,
                    window_started_at = CASE
                        WHEN auth_subject_bucket.window_started_at <= EXCLUDED.window_started_at
                                     - (CAST(? AS bigint) * INTERVAL '1 millisecond')
                        THEN EXCLUDED.window_started_at ELSE auth_subject_bucket.window_started_at END,
                    updated_at = EXCLUDED.updated_at
                """, key(subject), scope, ts(now), ts(now), windowMillis(), windowMillis()));
    }

    /** 인증 성공 시 주체 카운터를 비운다. 정상 사용자가 잠기지 않게 한다. */
    public void resetSubject(String scope, String subject) {
        transactions.executeWithoutResult(status -> jdbc.update(
                "DELETE FROM auth_subject_bucket WHERE subject_key = ? AND scope = ?",
                key(subject), scope));
    }

    /**
     * 창이 오래 지난 카운터를 정리한다. row 수를 실제 활성 주체 수준으로 유지하기 위한 것으로,
     * 정확성에는 관여하지 않는다.
     */
    public int purgeStale(Instant now, Duration retention, int limit) {
        long retentionMillis = Math.max(1, retention.toMillis());
        int removed = jdbc.update("""
                DELETE FROM auth_subject_bucket
                WHERE (subject_key, scope) IN (
                    SELECT subject_key, scope FROM auth_subject_bucket
                    WHERE updated_at <= CAST(? AS timestamptz) - (?::bigint * INTERVAL '1 millisecond')
                    ORDER BY updated_at
                    LIMIT ?
                )
                """, ts(now), retentionMillis, limit);
        removed += jdbc.update("""
                DELETE FROM auth_origin_bucket
                WHERE (origin_key, scope) IN (
                    SELECT origin_key, scope FROM auth_origin_bucket
                    WHERE updated_at <= CAST(? AS timestamptz) - (?::bigint * INTERVAL '1 millisecond')
                    ORDER BY updated_at
                    LIMIT ?
                )
                """, ts(now), retentionMillis, limit);
        return removed;
    }

    private boolean isOverSubjectLimit(String scope, String subject, Instant now) {
        List<Integer> failures = jdbc.query("""
                SELECT CASE
                    WHEN window_started_at <= CAST(? AS timestamptz) - (?::bigint * INTERVAL '1 millisecond') THEN 0
                    ELSE failures END
                FROM auth_subject_bucket
                WHERE subject_key = ? AND scope = ?
                """, (row, number) -> row.getInt(1), ts(now), windowMillis(), key(subject), scope);
        return failures.stream().findFirst().orElse(0) >= policy.subjectLimit();
    }

    private boolean consumeOrigin(String scope, String origin, Instant now) {
        List<Integer> attempts = jdbc.query("""
                INSERT INTO auth_origin_bucket (origin_key, scope, window_started_at, attempts, updated_at)
                VALUES (?, ?, CAST(? AS timestamptz), 1, CAST(? AS timestamptz))
                ON CONFLICT (origin_key, scope) DO UPDATE SET
                    attempts = CASE
                        WHEN auth_origin_bucket.window_started_at <= EXCLUDED.window_started_at
                                     - (CAST(? AS bigint) * INTERVAL '1 millisecond')
                        THEN 1 ELSE auth_origin_bucket.attempts + 1 END,
                    window_started_at = CASE
                        WHEN auth_origin_bucket.window_started_at <= EXCLUDED.window_started_at
                                     - (CAST(? AS bigint) * INTERVAL '1 millisecond')
                        THEN EXCLUDED.window_started_at ELSE auth_origin_bucket.window_started_at END,
                    updated_at = EXCLUDED.updated_at
                RETURNING attempts
                """, (row, number) -> row.getInt(1),
                key(origin), scope, ts(now), ts(now), windowMillis(), windowMillis());
        return attempts.stream().findFirst().orElse(Integer.MAX_VALUE) <= policy.originLimit();
    }

    private static String originScope(String scope) {
        return "EMAIL".equals(scope) ? "EMAIL" : "LOGIN";
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private long windowMillis() {
        return Math.max(1, policy.window().toMillis());
    }

    /**
     * 카운터 키. 원문 이메일·주소를 저장하지 않도록 SHA-256으로 단방향 축약한다.
     */
    public static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /** 인증 경로에서 공통으로 쓰는 거절 예외. 응답은 기존 인증 실패와 동일하게 유지한다. */
    public static AccountException rejected() {
        return new AccountException(AccountException.Code.INVALID_CREDENTIALS,
                "Email or password is incorrect");
    }

    /** 한도 확인과 실패 기록을 한 곳에서 처리하기 위한 헬퍼. */
    public <T> T guarded(String scope, String subject, String origin, Instant now, Supplier<T> action) {
        if (!tryAcquire(scope, subject, origin, now)) throw rejected();
        return action.get();
    }
}
