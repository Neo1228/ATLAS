package com.atlas.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.util.StringUtils;

/**
 * 공개 읽기 응답에만 캐시 정책을 부여합니다.
 *
 * <p>배경: Spring Security 는 기본적으로 모든 응답에
 * {@code Cache-Control: no-cache, no-store} 를 붙입니다. 인증이 필요 없는 공개 상품
 * 조회에도 그 헤더가 붙어 CDN·프록시가 아무것도 캐시할 수 없었습니다.
 *
 * <p>측정(#395): 요청당 5.84 ms 중 3.92 ms(67%)가 HTTP/프레임워크 처리입니다.
 * 이 비용은 애플리케이션 코드로 줄일 수 없지만, <b>요청을 받지 않게</b> 만들 수는
 * 있습니다. 공개 목록·상세·리뷰는 비로그인 사용자와 로그인 사용자가 같은 응답을
 * 받으므로 공유 캐시에 적합합니다.
 *
 * <p>안전성: 캐시된 목록으로는 주문할 수 없습니다. 주문 가능 판정과 가격 확정은
 * 상세·체크아웃에서 원본을 다시 검증합니다. 따라서 30초 지연은 탐색 화면에서
 * 허용 가능한 손실입니다.
 *
 * <p>Spring Security 의 기본 cache-control writer 는 아래에서 끕니다. 그러므로 이
 * writer 는 공개 경로에만 public 을 붙이는 것뿐 아니라, 나머지 모든 응답에
 * {@code no-store} 를 명시적으로 다시 붙여야 합니다. Authorization 헤더가 있는
 * 공개 경로도 공유 캐시에 넣지 않습니다(향후 개인화가 추가될 때의 방어선).
 */
public class PublicReadCacheHeaderWriter implements HeaderWriter {
    /** 공유 캐시(CDN·프록시)용 초. 짧게 두어 상품 변경이 빨리 반영되게 한다. */
    static final int SHARED_MAX_AGE_SECONDS = 30;

    /** 만료 후 백그라운드 재검증 동안 계속 응답할 수 있는 시간. */
    static final int STALE_WHILE_REVALIDATE_SECONDS = 60;

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        // 공개 경로라도 4xx/5xx 를 공유 캐시에 저장하지 않는다. 특히 404 는
        // 상품이 새로 생성되거나 공개 전환된 뒤에도 stale-negative 캐시가 남을 수 있다.
        if (isPublicRead(request) && response.getStatus() == HttpServletResponse.SC_OK) {
            response.setHeader(HttpHeaders.CACHE_CONTROL,
                    "public, max-age=0, s-maxage=" + SHARED_MAX_AGE_SECONDS
                            + ", stale-while-revalidate=" + STALE_WHILE_REVALIDATE_SECONDS);
            // Cache key 에 Authorization 을 포함한다. 공유 캐시가 먼저 응답하면
            // origin 의 bypass 로직은 실행되지 않으므로, Vary 로 익명 변형과
            // Authorization 변형을 분리해야 한다.
            response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
            response.setHeader("Pragma", null);
            response.setHeader("Expires", null);
            return;
        }
        // Security 기본 writer 를 껐으므로 사용자별·인증 실패 응답에 no-store 를 직접 보장한다.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }

    /**
     * 공개 읽기 경로인지 판정합니다.
     *
     * <p>GET 만 대상으로 합니다. 목록·상세·리뷰·캠페인이 여기 해당하며,
     * 인증이 필요한 쓰기·사용자별 경로는 제외됩니다.
     */
    static boolean isPublicRead(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        if (StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) return false;
        String path = request.getRequestURI();
        if (path == null) return false;
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/api/products")
                || path.matches("^/api/products/[^/]+$")
                || path.matches("^/api/products/[^/]+/reviews$")
                || path.matches("^/api/campaigns/[^/]+$");
    }
}
