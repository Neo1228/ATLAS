package com.atlas.account.service.impl;

import com.atlas.account.repository.AuthThrottleRepository;
import com.atlas.account.service.AuthAttemptLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthAttemptLimiterImpl implements AuthAttemptLimiter {
    /** Windows and limits are settings so operators can tune them from measured failure rates. */
    private record Policy(Duration window, int subjectLimit, int originLimit) {
        private Policy {
            Objects.requireNonNull(window);
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("throttle window must be positive");
            }
            if (subjectLimit < 1 || originLimit < 1) {
                throw new IllegalArgumentException("throttle limits must be positive");
            }
        }
    }

    private final AuthThrottleRepository counters;
    private final TransactionTemplate transactions;
    private final Policy policy;

    public AuthAttemptLimiterImpl(
            AuthThrottleRepository counters,
            PlatformTransactionManager transactionManager,
            @Value("${atlas.auth.throttle.window:PT15M}") Duration window,
            @Value("${atlas.auth.throttle.subject-limit:10}") int subjectLimit,
            @Value("${atlas.auth.throttle.origin-limit:60}") int originLimit) {
        this.counters = Objects.requireNonNull(counters);
        // 실패 카운터는 인증이 실패로 끝나 rollback되는 transaction과 별개로 남아야 한다.
        // 호출 트랜잭션이 있으면 그 안에서 rollback되므로 REQUIRES_NEW로 분리한다.
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = template;
        this.policy = new Policy(window, subjectLimit, originLimit);
    }

    @Override
    public boolean tryAcquire(String scope, String subject, String origin, Instant now) {
        if (counters.activeSubjectFailures(scope, key(subject), now, windowMillis()) >= policy.subjectLimit()) {
            return false;
        }
        // 소비도 독립 transaction이어야 한다. 인증이 실패해 바깥 transaction이 rollback돼도
        // 시도 횟수는 남아야 한도가 실제로 줄어든다.
        Integer attempts = transactions.execute(status ->
                counters.incrementOriginAttempts(originScope(scope), key(origin), now, windowMillis()));
        return attempts != null && attempts <= policy.originLimit();
    }

    @Override
    public void recordSubjectFailure(String scope, String subject, Instant now) {
        transactions.executeWithoutResult(status ->
                counters.recordSubjectFailure(scope, key(subject), now, windowMillis()));
    }

    @Override
    public void resetSubject(String scope, String subject) {
        transactions.executeWithoutResult(status -> counters.deleteSubject(scope, key(subject)));
    }

    @Override
    public int purgeStale(Instant now, Duration retention, int limit) {
        return counters.purgeStale(now, Math.max(1, retention.toMillis()), limit);
    }

    private static String originScope(String scope) {
        return "EMAIL".equals(scope) ? "EMAIL" : "LOGIN";
    }

    private long windowMillis() {
        return Math.max(1, policy.window().toMillis());
    }

    /** 카운터 키. 원문 이메일 주소를 저장하지 않도록 SHA-256으로 단방향 축약한다. */
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
}
