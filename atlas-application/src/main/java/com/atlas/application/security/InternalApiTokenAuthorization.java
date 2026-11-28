package com.atlas.application.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * 내부 주문-재고 API 의 서비스 토큰 검사.
 *
 * 경로 판정은 하지 않는다. {@code ApiSecurityConfiguration} 이 이 검사를 붙일 경로를 고르고, 그 판정은
 * Spring Security 의 요청 매처가 컨테이너가 디코딩·정규화한 경로(context-path 제외)로 한다.
 * 예전에는 서블릿 필터가 원본 {@code getRequestURI()} 의 접두사를 비교해서 {@code /api/%69nternal/...}
 * 이나 context-path 가 붙은 요청은 토큰 없이 통과했다.
 */
@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class InternalApiTokenAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
    private static final String TOKEN_HEADER = "X-Atlas-Service-Token";
    private final byte[] expectedToken;

    public InternalApiTokenAuthorization(
            @Value("${atlas.order-inventory-reservation.api-token:}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN must contain at least 32 characters");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        String suppliedToken = context.getRequest().getHeader(TOKEN_HEADER);
        byte[] supplied = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        return new AuthorizationDecision(MessageDigest.isEqual(expectedToken, supplied));
    }
}
