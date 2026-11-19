package com.atlas.application.controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 시도 제한에서 쓰는 클라이언트 식별자.
 *
 * <p>프록시 뒤에서는 {@code getRemoteAddr()}가 프록시 주소이므로, 신뢰 경계가 설정한 첫
 * {@code X-Forwarded-For} 값을 사용한다. 헤더는 공격자가 임의로 넣을 수 있으므로 그 자체를
 * 인가 근거로 쓰지 않는다. 목적은 한 출처가 대량 요청을 보내는 것을 막는 것이고, 위조된
 * 헤더는 오히려 자기 출처의 카운터만 소모시키는 방향으로 동작한다.
 */
public final class ClientOrigin {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_LENGTH = 64;

    private ClientOrigin() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) return "unknown";
        String forwarded = request.getHeader(FORWARDED_FOR);
        String candidate = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0];
        if (candidate == null || candidate.isBlank()) return "unknown";
        String trimmed = candidate.trim();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
