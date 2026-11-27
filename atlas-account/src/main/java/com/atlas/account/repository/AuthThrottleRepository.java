package com.atlas.account.repository;

import com.atlas.account.model.AuthAttemptScope.OriginBucket;
import com.atlas.account.model.AuthAttemptScope.SubjectBucket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 인증 시도 제한 카운터의 저장소.
 *
 * <p>카운터는 고정 시간 창으로 나눈 고정 크기 row를 재사용한다. 창이 바뀌면 같은 row를 새 창으로 덮어쓰므로
 * (주체 수 × 창 수)로 무한히 늘지 않는다. 키는 호출자가 원문을 저장하지 않도록 이미 축약해서 넘긴다.
 * 트랜잭션 경계와 정책 판단은 호출하는 {@code AuthAttemptLimiter}가 맡는다.
 */
@Repository
public class AuthThrottleRepository {
    private final JdbcTemplate jdbc;

    public AuthThrottleRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * 현재 창에 주체 시도 1을 예약한다. 한도에 닿았으면 아무것도 바꾸지 않고 false를 돌려준다.
     *
     * <p>읽고 판단한 뒤 나중에 기록하면, 기록 전에 들어온 동시 시도가 모두 같은 값을 읽어 한도를 넘는다
     * (EXP-118 H1: 오답 90건 동시, 인스턴스 1대에서도 한도 10을 넘겼다). 비교와 증가를 한 문장에 두면
     * 같은 행의 upsert가 직렬화되므로 인스턴스 수와 무관하게 한도만큼만 통과한다.
     *
     * <p>열 이름 {@code failures}는 이제 "창 안에서 검증까지 간 시도 수"다. rolling 배포 동안 old jar가
     * 같은 열을 읽고 쓰므로 이름을 바꾸지 않는다(old jar는 실패 뒤에 1을 더하고, 이 코드는 검증 전에 1을 더해
     * 한 시도가 두 번 세지지 않는다).
     */
    public boolean reserveSubjectAttempt(SubjectBucket scope, String subjectKey, Instant now, long windowMillis,
            int limit) {
        List<Integer> reserved = jdbc.query("""
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
                WHERE auth_subject_bucket.window_started_at <= EXCLUDED.window_started_at
                              - (CAST(? AS bigint) * INTERVAL '1 millisecond')
                   OR auth_subject_bucket.failures < ?
                RETURNING failures
                """, (row, number) -> row.getInt(1),
                subjectKey, scope.name(), ts(now), ts(now), windowMillis, windowMillis, windowMillis, limit);
        return !reserved.isEmpty();
    }

    /** 예약한 주체 시도 1을 돌려준다. 출처 한도에 막혀 검증하지 않은 시도가 주체 한도를 쓰지 않게 한다. */
    public void releaseSubjectAttempt(SubjectBucket scope, String subjectKey) {
        jdbc.update("""
                UPDATE auth_subject_bucket SET failures = failures - 1
                WHERE subject_key = ? AND scope = ? AND failures > 0
                """, subjectKey, scope.name());
    }

    /** 출처 시도를 1 더하고, 더한 뒤의 현재 창 시도 횟수를 돌려준다. */
    public int incrementOriginAttempts(OriginBucket scope, String originKey, Instant now, long windowMillis) {
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
                originKey, scope.name(), ts(now), ts(now), windowMillis, windowMillis);
        return attempts.stream().findFirst().orElse(Integer.MAX_VALUE);
    }

    public void deleteSubject(SubjectBucket scope, String subjectKey) {
        jdbc.update("DELETE FROM auth_subject_bucket WHERE subject_key = ? AND scope = ?", subjectKey, scope.name());
    }

    /** 오래된 카운터를 지운다. 정확성에는 관여하지 않고 row 수를 활성 주체 수준으로 유지한다. */
    public int purgeStale(Instant now, long retentionMillis, int limit) {
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

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
