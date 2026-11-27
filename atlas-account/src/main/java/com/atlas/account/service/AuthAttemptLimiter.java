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
     * 주체 시도 1을 예약하고 출처 시도를 1 소비한다. 둘 중 하나라도 한도에 닿았으면 false다.
     *
     * <p>주체 시도는 검증 전에 원자적으로 예약한다. 예약된 시도는 결과와 무관하게 창 안의 한 번으로 남고,
     * 성공한 인증만 {@link #resetSubject}로 비운다. 실패를 검증 뒤에 기록하던 방식은 동시 시도가 기록 전에
     * 모두 통과해 한도를 넘겼다(EXP-118 H1). 출처 한도에 막힌 시도는 주체 예약을 돌려준다.
     *
     * <p>주체 축이 없는 범위(EMAIL)는 출처 한도만 본다.
     */
    boolean tryAcquire(AuthAttemptScope scope, String subject, String origin, Instant now);

    /** 인증 성공 시 주체 카운터를 비운다. 정상 사용자가 잠기지 않게 한다. */
    void resetSubject(SubjectBucket scope, String subject);

    /**
     * 창이 오래 지난 카운터를 정리한다. row 수를 실제 활성 주체 수준으로 유지하기 위한 것으로,
     * 정확성에는 관여하지 않는다.
     */
    int purgeStale(Instant now, Duration retention, int limit);
}
