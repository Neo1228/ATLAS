package com.atlas.account.service;

import com.atlas.account.model.AuthAttemptScope.SubjectBucket;
import com.atlas.account.model.AuthAttemptScope;
import com.atlas.account.repository.AuthThrottleRepository;
import java.time.Duration;
import java.time.Instant;

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
public interface AuthAttemptLimiter {
    /**
     * 주체 출처 한도가 모두 남았는지 확인하고, 남았다면 출처 시도를 1 소비한다.
     *
     * <p>주체 실패는 인증이 실패한 뒤에만 기록해야 하므로 여기서 소비하지 않는다.
     *
     * <p>주체 축이 없는 범위(EMAIL)는 출처 한도만 본다.
     */
    boolean tryAcquire(AuthAttemptScope scope, String subject, String origin, Instant now);

    /** 주체 실패를 1 기록한다. 성공한 인증은 {@link #resetSubject}로 되돌린다. */
    void recordSubjectFailure(SubjectBucket scope, String subject, Instant now);

    /** 인증 성공 시 주체 카운터를 비운다. 정상 사용자가 잠기지 않게 한다. */
    void resetSubject(SubjectBucket scope, String subject);

    /**
     * 창이 오래 지난 카운터를 정리한다. row 수를 실제 활성 주체 수준으로 유지하기 위한 것으로,
     * 정확성에는 관여하지 않는다.
     */
    int purgeStale(Instant now, Duration retention, int limit);
}
