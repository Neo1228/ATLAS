package com.atlas.order.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 반품 요청 기간과 검수 마감 규칙(ADR-0085, ADR-0089).
 *
 * <p>값은 {@code application.yml}의 {@code atlas.order.return.*}이고(환경 변수로 덮어쓴다), 설정이 없을 때의
 * 값은 아래 상수다. 7일과 5영업일은 근거 있는 정책이 아니라 가정이므로 코드가 아니라 설정으로 바꾼다.
 *
 * <p>규칙은 둘이다. (1) 반품 기간은 반품 줄이 속한 출고의 {@code delivered_at}부터 정확히 {@code window}
 * 시간이고 경계를 포함한다. 달력일이 아니라 시간이므로 시간대와 무관하다. (2) 검수 마감은 수령 시각의
 * 달력일에서 영업일을 세어 정한다. 영업일은 월~금이고 <b>공휴일은 지원하지 않는다</b>(공휴일이 끼면 마감이
 * 실제보다 빨리 온다). 날짜는 {@code businessZone}의 달력으로 센다.
 */
@Component
public record ReturnPolicy(
        @Value("${atlas.order.return.window:" + ReturnPolicy.DEFAULT_WINDOW + "}") Duration window,
        @Value("${atlas.order.return.window-enforced:" + ReturnPolicy.DEFAULT_WINDOW_ENFORCED + "}")
                boolean windowEnforced,
        @Value("${atlas.order.return.inspection-business-days:" + ReturnPolicy.DEFAULT_INSPECTION_BUSINESS_DAYS + "}")
                int inspectionBusinessDays,
        @Value("${atlas.order.return.business-zone:" + ReturnPolicy.DEFAULT_BUSINESS_ZONE + "}")
                ZoneId businessZone) {
    /** 코드의 기본값. 같은 값을 {@code application.yml}에 적으며 두 곳이 같은지는 테스트가 확인한다. */
    public static final String DEFAULT_WINDOW = "P7D";
    public static final String DEFAULT_WINDOW_ENFORCED = "true";
    public static final int DEFAULT_INSPECTION_BUSINESS_DAYS = 5;
    public static final String DEFAULT_BUSINESS_ZONE = "Asia/Seoul";

    /** 기간의 허용 범위. 오입력으로 기간이 0에 가깝거나 사실상 무제한이 되는 것을 시작 시점에 막는다. */
    public static final Duration MIN_WINDOW = Duration.ofHours(1);
    public static final Duration MAX_WINDOW = Duration.ofDays(365);
    public static final int MAX_INSPECTION_BUSINESS_DAYS = 30;

    public ReturnPolicy {
        if (window == null || window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("return window must be between 1 hour and 365 days");
        }
        if (inspectionBusinessDays < 1 || inspectionBusinessDays > MAX_INSPECTION_BUSINESS_DAYS) {
            throw new IllegalArgumentException("inspection business days must be between 1 and 30");
        }
        if (businessZone == null) {
            throw new IllegalArgumentException("business zone is required");
        }
    }

    /** 요청 시각이 {@code deliveredAt + window} 이하이면 받는다(경계 포함). 기간 검사를 끄면 항상 받는다. */
    public boolean allowsReturn(Instant deliveredAt, Instant now) {
        return !windowEnforced || !now.isAfter(returnWindowEndsAt(deliveredAt));
    }

    /** 이 시각까지(포함) 반품을 요청할 수 있다. */
    public Instant returnWindowEndsAt(Instant deliveredAt) {
        return deliveredAt.plus(window);
    }

    /**
     * 검수 마감 시각(배타적 끝). 수령 시각의 현지 날짜에서 {@code inspectionBusinessDays}영업일 뒤가 마감일이고
     * 마감일 다음 날 00:00이다. 수령일이 주말이면 다음 월요일이 1영업일이다.
     */
    public Instant inspectionDueAt(Instant receivedAt) {
        LocalDate day = receivedAt.atZone(businessZone).toLocalDate();
        int remaining = inspectionBusinessDays;
        while (remaining > 0) {
            day = day.plusDays(1);
            if (isBusinessDay(day)) remaining--;
        }
        return day.plusDays(1).atStartOfDay(businessZone).toInstant();
    }

    /** 마감 시각 정각부터 초과다. 검수가 끝났는지는 호출하는 쪽이 상태로 판단한다. */
    public boolean isInspectionOverdue(Instant receivedAt, Instant now) {
        return !now.isBefore(inspectionDueAt(receivedAt));
    }

    /**
     * 이 시간보다 덜 지난 수령 건은 초과일 수 없다는 하한. 초과 후보를 DB에서 좁힐 때 쓴다. 마감은 수령일 00:00
     * (수령 시각보다 최대 24시간 앞)에서 N영업일 + 1일 뒤이므로 수령 시각 기준 달력으로 N일 이상이다. 시간대의
     * 일광 절약 시간 같은 1시간 오차까지 덮도록 하루를 더 느슨하게 잡는다(정확한 판정은 {@link #isInspectionOverdue}).
     */
    public Duration minimumInspectionSpan() {
        return Duration.ofDays(inspectionBusinessDays - 1L);
    }

    private static boolean isBusinessDay(LocalDate day) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY;
    }
}
