package com.atlas.admission.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seller가 값을 주지 않을 때 쓰는 K(checkout slot 수)와 ticket TTL 기본값, 그리고 요청 값의 상한.
 *
 * <p>운영 값은 {@code application.yml}의 {@code atlas.admission.*}이고(환경 변수로 덮어쓴다), 설정이 없을 때의
 * 값은 아래 상수다. 기본값은 EXP-086의 가정 기반
 * 민감도에서 골랐고(ADR-0083), 상한은 튜닝 값이 아니라 오입력과 남용으로 slot이 사실상 무제한이 되는 것을
 * 막는 값이다.
 *
 * <p>{@code maxLiveTicketsPerBuyer}는 같은 Seller-SKU에서 한 구매자가 동시에 가질 수 있는 live ticket
 * (WAITING·CHECKOUT_ACTIVE·CHECKOUT_STARTED) 수이고 0이면 검사하지 않는다(ADR-0083·ADR-0088).
 */
@Component
public record AdmissionLimits(
        @Value("${atlas.admission.default-max-active-checkouts:" + AdmissionLimits.DEFAULT_MAX_ACTIVE_CHECKOUTS + "}")
        int defaultMaxActiveCheckouts,
        @Value("${atlas.admission.max-active-checkouts-limit:" + AdmissionLimits.MAX_ACTIVE_CHECKOUTS_LIMIT + "}")
        int maxActiveCheckoutsLimit,
        @Value("${atlas.admission.default-ticket-ttl:" + AdmissionLimits.DEFAULT_TICKET_TTL + "}")
        Duration defaultTicketTtl,
        @Value("${atlas.admission.ticket-ttl-limit:" + AdmissionLimits.TICKET_TTL_LIMIT + "}")
        Duration ticketTtlLimit,
        @Value("${atlas.admission.max-live-tickets-per-buyer:" + AdmissionLimits.MAX_LIVE_TICKETS_PER_BUYER + "}")
        int maxLiveTicketsPerBuyer) {
    /** 코드의 기본값. 같은 값을 {@code application.yml}에 적으며 두 곳이 같은지는 테스트가 확인한다. */
    public static final int DEFAULT_MAX_ACTIVE_CHECKOUTS = 50;
    public static final int MAX_ACTIVE_CHECKOUTS_LIMIT = 10_000;
    public static final String DEFAULT_TICKET_TTL = "PT2M";
    public static final String TICKET_TTL_LIMIT = "PT2H";
    public static final int MAX_LIVE_TICKETS_PER_BUYER = 1;

    public AdmissionLimits {
        if (defaultMaxActiveCheckouts < 1 || defaultMaxActiveCheckouts > maxActiveCheckoutsLimit) {
            throw new IllegalArgumentException("default K must be between 1 and the K limit");
        }
        if (!isAtLeastOneSecond(defaultTicketTtl) || !isAtLeastOneSecond(ticketTtlLimit)
                || defaultTicketTtl.compareTo(ticketTtlLimit) > 0) {
            throw new IllegalArgumentException("ticket TTL must be >= 1s and the default within the TTL limit");
        }
        if (maxLiveTicketsPerBuyer < 0) {
            throw new IllegalArgumentException("max live tickets per buyer must be 0 (off) or positive");
        }
    }

    /** 요청이 K를 주지 않으면 기본값을 쓰고, 값이 있으면 그대로 돌려준다. 범위 검사는 {@link #allowsMaxActiveCheckouts}가 한다. */
    public int resolveMaxActiveCheckouts(Integer requested) {
        return requested == null ? defaultMaxActiveCheckouts : requested;
    }

    public boolean allowsMaxActiveCheckouts(int maxActiveCheckouts) {
        return maxActiveCheckouts >= 1 && maxActiveCheckouts <= maxActiveCheckoutsLimit;
    }

    /** 요청이 TTL을 주지 않으면 기본값을 쓰고, 값이 있으면 그대로 돌려준다. 범위 검사는 {@link #allowsTicketTtl}가 한다. */
    public Duration resolveTicketTtl(Duration requested) {
        return requested == null ? defaultTicketTtl : requested;
    }

    public boolean allowsTicketTtl(Duration ticketTtl) {
        return isAtLeastOneSecond(ticketTtl) && ticketTtl.compareTo(ticketTtlLimit) <= 0;
    }

    private static boolean isAtLeastOneSecond(Duration value) {
        return value != null && value.toSeconds() > 0;
    }
}
