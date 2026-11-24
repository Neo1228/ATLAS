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

    /** 현재 창 안의 주체 실패 횟수. 창이 지났거나 기록이 없으면 0이다. */
    public int activeSubjectFailures(SubjectBucket scope, String subjectKey, Instant now, long windowMillis) {
        List<Integer> failures = jdbc.query("""
                SELECT CASE
                    WHEN window_started_at <= CAST(? AS timestamptz) - (?::bigint * INTERVAL '1 millisecond') THEN 0
                    ELSE failures END
                FROM auth_subject_bucket
                WHERE subject_key = ? AND scope = ?
                """, (row, number) -> row.getInt(1), ts(now), windowMillis, subjectKey, scope.name());
        return failures.stream().findFirst().orElse(0);
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

    public void recordSubjectFailure(SubjectBucket scope, String subjectKey, Instant now, long windowMillis) {
        jdbc.update("""
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
                """, subjectKey, scope.name(), ts(now), ts(now), windowMillis, windowMillis);
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
