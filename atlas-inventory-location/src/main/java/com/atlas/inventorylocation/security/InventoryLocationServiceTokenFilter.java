package com.atlas.inventorylocation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 이 서비스의 모든 요청에 서비스 토큰을 요구한다. 열어 두는 것은 {@link #PUBLIC_PATHS} 의 actuator 경로뿐이다.
 *
 * 경로는 원본 {@code getRequestURI()} 가 아니라 컨테이너가 디코딩·정규화한 servletPath + pathInfo 로 본다.
 * 여기에는 context-path 와 {@code ;} 경로 파라미터가 빠지고 {@code %69}, {@code //}, {@code ..} 가 풀려 있어
 * DispatcherServlet 이 컨트롤러를 고를 때 보는 경로와 같다. 예전에는 원본 URI 의 {@code /internal/v1/} 접두사만
 * 검사해서 인코딩·{@code ;} 변형이나 context-path 가 붙은 요청이 토큰 없이 컨트롤러에 닿았다.
 */
@Component
public class InventoryLocationServiceTokenFilter extends OncePerRequestFilter {
    private static final String TOKEN_HEADER = "X-Atlas-Service-Token";
    // application.yml 의 management.endpoints.web.exposure 와 맞춘다. 컨테이너 probe 와 scrape 가 토큰 없이 부른다.
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/info", "/actuator/prometheus");
    private final byte[] expectedToken;

    public InventoryLocationServiceTokenFilter(@Value("${inventory.service-token:}") String serviceToken) {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalStateException("INVENTORY_LOCATION_SERVICE_TOKEN must contain at least 32 characters");
        }
        expectedToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (PUBLIC_PATHS.contains(applicationPath(request))) {
            filterChain.doFilter(request, response);
            return;
        }
        String suppliedToken = request.getHeader(TOKEN_HEADER);
        byte[] supplied = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String applicationPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }
}
