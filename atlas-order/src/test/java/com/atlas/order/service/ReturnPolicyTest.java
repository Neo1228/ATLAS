package com.atlas.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ReturnPolicyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant DELIVERED = Instant.parse("2026-11-02T03:04:05.123456Z");

    private static ReturnPolicy policy(int businessDays) {
        return new ReturnPolicy(Duration.ofDays(7), true, businessDays, SEOUL);
    }

    @Test
    void windowBoundaryIsInclusive() {
        ReturnPolicy policy = policy(5);
        Instant end = DELIVERED.plus(Duration.ofDays(7));

        assertThat(policy.returnWindowEndsAt(DELIVERED)).isEqualTo(end);
        assertThat(policy.allowsReturn(DELIVERED, end.minusSeconds(1))).isTrue();
        assertThat(policy.allowsReturn(DELIVERED, end)).isTrue();
        assertThat(policy.allowsReturn(DELIVERED, end.plusNanos(1))).isFalse();
        assertThat(policy.allowsReturn(DELIVERED, end.plusSeconds(1))).isFalse();
    }

    @Test
    void disablingEnforcementAllowsAnyAge() {
        ReturnPolicy disabled = new ReturnPolicy(Duration.ofDays(7), false, 5, SEOUL);

        assertThat(disabled.allowsReturn(DELIVERED, DELIVERED.plus(Duration.ofDays(4000)))).isTrue();
    }

    @ParameterizedTest(name = "{0} +{1}영업일 -> 마감 {2}")
    @CsvSource({
            // 월요일 수령 + 5영업일 = 다음 월요일, 마감은 그 다음 날 00:00
            "2026-11-30T10:00, 5, 2026-12-08T00:00",
            // 금요일 + 5영업일 = 다음 금요일
            "2026-11-27T10:00, 5, 2026-12-05T00:00",
            // 토요일·일요일 수령은 다음 월요일이 1영업일이다
            "2026-11-28T10:00, 5, 2026-12-05T00:00",
            "2026-11-29T10:00, 5, 2026-12-05T00:00",
            // 금요일 + 2영업일 = 화요일, 금요일 + 1영업일 = 월요일
            "2026-11-27T10:00, 2, 2026-12-02T00:00",
            "2026-11-27T10:00, 1, 2026-12-01T00:00",
            // 목요일 + 1영업일 = 금요일
            "2026-12-03T10:00, 1, 2026-12-05T00:00",
            // 연말: 공휴일(1월 1일)은 지원하지 않으므로 평일로 센다
            "2026-12-30T10:00, 5, 2027-01-07T00:00"})
    void inspectionDueCountsBusinessDaysAndSkipsWeekends(LocalDateTime received, int businessDays, LocalDateTime due) {
        Instant dueAt = policy(businessDays).inspectionDueAt(received.atZone(SEOUL).toInstant());

        assertThat(dueAt).isEqualTo(due.atZone(SEOUL).toInstant());
    }

    @Test
    void dayBoundaryFollowsTheBusinessZoneNotUtc() {
        ReturnPolicy policy = policy(1);
        // 목요일 23:59:59(서울)와 금요일 00:00:00(서울)는 UTC로는 같은 날 14:59:59·15:00:00이다.
        Instant thursdayLate = Instant.parse("2026-12-03T14:59:59Z");
        Instant fridayStart = Instant.parse("2026-12-03T15:00:00Z");

        assertThat(policy.inspectionDueAt(thursdayLate)).isEqualTo(Instant.parse("2026-12-04T15:00:00Z"));
        assertThat(policy.inspectionDueAt(fridayStart)).isEqualTo(Instant.parse("2026-12-07T15:00:00Z"));
        assertThat(new ReturnPolicy(Duration.ofDays(7), true, 1, ZoneId.of("UTC")).inspectionDueAt(thursdayLate))
                .isEqualTo(Instant.parse("2026-12-05T00:00:00Z"));
    }

    @Test
    void overdueStartsExactlyAtTheExclusiveEnd() {
        ReturnPolicy policy = policy(5);
        Instant received = Instant.parse("2026-11-30T01:00:00Z");
        Instant dueAt = policy.inspectionDueAt(received);

        assertThat(policy.isInspectionOverdue(received, dueAt.minusSeconds(1))).isFalse();
        assertThat(policy.isInspectionOverdue(received, dueAt.minusNanos(1))).isFalse();
        assertThat(policy.isInspectionOverdue(received, dueAt)).isTrue();
        assertThat(policy.isInspectionOverdue(received, dueAt.plusSeconds(1))).isTrue();
    }

    @Test
    void candidateFilterNeverExcludesAnOverdueReturn() {
        // DB에서 후보를 좁히는 하한(minimumInspectionSpan)이 정확한 마감보다 늦지 않음을 시간대(일광 절약 포함)·일수별로 훑는다.
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        for (ZoneId zone : new ZoneId[] {SEOUL, ZoneId.of("America/New_York")}) {
            for (int days = 1; days <= 10; days++) {
                ReturnPolicy policy = new ReturnPolicy(Duration.ofDays(7), true, days, zone);
                for (int hour = 0; hour < 24 * 45; hour++) {
                    Instant received = start.plus(Duration.ofHours(hour));
                    assertThat(policy.inspectionDueAt(received))
                            .isAfterOrEqualTo(received.plus(policy.minimumInspectionSpan()));
                }
            }
        }
    }

    @Test
    void configuredValuesOutsideTheBoundsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new ReturnPolicy(null, true, 5, SEOUL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofMinutes(59), true, 5, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofDays(366), true, 5, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofDays(7), true, 0, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofDays(7), true, 31, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ofDays(7), true, 5, null))
                .isInstanceOf(IllegalArgumentException.class);
        // 기간 검사를 꺼도 값 범위는 검증한다: 다시 켤 때 시작이 실패하는 일을 막는다.
        assertThatThrownBy(() -> new ReturnPolicy(Duration.ZERO, false, 5, SEOUL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ReturnPolicy(Duration.ofHours(1), true, 1, SEOUL)).doesNotThrowAnyException();
        assertThatCode(() -> new ReturnPolicy(Duration.ofDays(365), true, 30, SEOUL)).doesNotThrowAnyException();
    }
}
