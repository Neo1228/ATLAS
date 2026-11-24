package com.atlas.application.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 시도 제한에서 쓰는 클라이언트 식별자.
 *
 * <p>프록시 뒤에서는 {@code getRemoteAddr()}가 프록시 주소이므로 {@code X-Forwarded-For}의
 * 첫 값을 사용한다. 이 값은 앞단 프록시가 클라이언트가 보낸 헤더를 지우고 접속 주소로
 * 덮어쓴다는 배포 전제 위에서만 믿을 수 있다(#484에서 이 전제를 받아들이기로 결정).
 *
 * <p>전제가 깨져 클라이언트가 헤더를 직접 보낼 수 있으면 요청마다 값을 바꿔 출처별 한도를
 * 우회하거나, 다른 사용자의 주소를 적어 그 출처의 카운터를 소모시킬 수 있다. 계정별 실패
 * 한도는 출처와 무관하게 적용된다. 프록시를 거치지 않는 배포라면 헤더를 믿지 말아야 한다.
 * 이 값은 한도 계산에만 쓰고 인가 근거로 쓰지 않는다.
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
