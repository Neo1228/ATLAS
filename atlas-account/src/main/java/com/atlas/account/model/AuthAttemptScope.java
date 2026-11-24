package com.atlas.account.model;

import java.util.Optional;

/**
 * 인증 시도 제한을 적용하는 요청 범위.
 *
 * <p>카운터는 두 테이블에 나뉘고 테이블마다 허용하는 scope 값이 다르다. 그래서 요청 범위가 어느 버킷 값에
 * 기록되는지를 이 enum 이 정하고, 저장소는 테이블별 enum({@link OriginBucket}, {@link SubjectBucket})만
 * 받는다. 제약에 없는 값은 타입으로 표현할 수 없다.
 *
 * <ul>
 *   <li>LOGIN: 출처 LOGIN, 주체 LOGIN.</li>
 *   <li>EMAIL: 출처 EMAIL 만 센다. 메일 발송 요청은 인증 실패가 없으므로 주체 축이 없다.</li>
 *   <li>MFA: 주체 MFA. 출처 제약에 MFA 가 없으므로 LOGIN 출처를 쓰고, 출처 키는 MFA 주체 digest 라
 *       IP 주소 키와 섞이지 않는다.</li>
 * </ul>
 */
public enum AuthAttemptScope {
    LOGIN(OriginBucket.LOGIN, SubjectBucket.LOGIN),
    EMAIL(OriginBucket.EMAIL, null),
    MFA(OriginBucket.LOGIN, SubjectBucket.MFA);

    /** {@code auth_origin_bucket.scope}. 값 집합은 {@code auth_origin_bucket_scope_check} 제약과 같다. */
    public enum OriginBucket {
        LOGIN,
        EMAIL
    }

    /** {@code auth_subject_bucket.scope}. 값 집합은 {@code auth_subject_bucket_scope_check} 제약과 같다. */
    public enum SubjectBucket {
        LOGIN,
        MFA
    }

    private final OriginBucket originBucket;
    private final SubjectBucket subjectBucket;

    AuthAttemptScope(OriginBucket originBucket, SubjectBucket subjectBucket) {
        this.originBucket = originBucket;
        this.subjectBucket = subjectBucket;
    }

    public OriginBucket originBucket() {
        return originBucket;
    }

    /** 주체 실패를 세지 않는 범위(EMAIL)는 비어 있다. */
    public Optional<SubjectBucket> subjectBucket() {
        return Optional.ofNullable(subjectBucket);
    }
}
