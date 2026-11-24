package com.atlas.inventorylocation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InventoryLocationServiceTokenFilter extends OncePerRequestFilter {
    private static final String TOKEN_HEADER = "X-Atlas-Service-Token";
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
        if (!request.getRequestURI().startsWith("/internal/v1/")) {
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
