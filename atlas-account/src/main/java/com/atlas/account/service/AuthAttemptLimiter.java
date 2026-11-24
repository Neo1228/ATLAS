package com.atlas.account.service;

import com.atlas.account.repository.AuthThrottleRepository;
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

/**
 * 인증 시도 빈도를 제한한다.
 *
 * <p>두 축을 쓴다. 주체(정규화 이메일) 축은 한 계정에 대한 비밀번호 추측을 막고, 출처(클라이언트
 * 주소) 축은 한 출처가 여러 계정을 훑거나 메일을 대량 발송하는 것을 막는다.
 *
 * <p>거절은 반드시 <b>비밀번호 검증 전에</b> 일어나야 한다. PBKDF2 검증 1회가 수십 ms의 CPU를
 * 쓰므로, 검증 뒤에 막으면 자원 고갈을 그대로 허용하게 된다.
 *
 * <p>카운터 저장은 {@code AuthThrottleRepository}가 맡고, 이 클래스는 정책과 트랜잭션 경계만 가진다.
 */
@Service
public class AuthAttemptLimiter {
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

    public AuthAttemptLimiter(
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

    /**
     * 주체 출처 한도가 모두 남았는지 확인하고, 남았다면 출처 시도를 1 소비한다.
     *
     * <p>주체 실패는 인증이 실패한 뒤에만 기록해야 하므로 여기서 소비하지 않는다.
     */
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

    /** 주체 실패를 1 기록한다. 성공한 인증은 {@link #resetSubject}로 되돌린다. */
    public void recordSubjectFailure(String scope, String subject, Instant now) {
        transactions.executeWithoutResult(status ->
                counters.recordSubjectFailure(scope, key(subject), now, windowMillis()));
    }

    /** 인증 성공 시 주체 카운터를 비운다. 정상 사용자가 잠기지 않게 한다. */
    public void resetSubject(String scope, String subject) {
        transactions.executeWithoutResult(status -> counters.deleteSubject(scope, key(subject)));
    }

    /**
     * 창이 오래 지난 카운터를 정리한다. row 수를 실제 활성 주체 수준으로 유지하기 위한 것으로,
     * 정확성에는 관여하지 않는다.
     */
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
