package com.atlas.application.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        String previousRequestId = MDC.get(REQUEST_ID_MDC_KEY);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) MDC.remove(REQUEST_ID_MDC_KEY);
            else MDC.put(REQUEST_ID_MDC_KEY, previousRequestId);
        }
    }
}
