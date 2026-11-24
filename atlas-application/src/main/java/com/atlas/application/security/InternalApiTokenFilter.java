package com.atlas.application.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class InternalApiTokenFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/api/internal/order-inventory/";
    private static final String TOKEN_HEADER = "X-Atlas-Service-Token";
    private final byte[] expectedToken;

    public InternalApiTokenFilter(
            @Value("${atlas.order-inventory-reservation.api-token:}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("ATLAS_ORDER_INVENTORY_RESERVATION_API_TOKEN must contain at least 32 characters");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(PATH_PREFIX)) {
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
}
