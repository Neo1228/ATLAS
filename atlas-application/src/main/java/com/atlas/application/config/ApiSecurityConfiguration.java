package com.atlas.application.config;

import com.atlas.application.security.AccountOwnershipInterceptor;
import com.atlas.application.security.PublicReadCacheHeaderWriter;
import com.atlas.application.security.InternalApiTokenAuthorizationManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration implements WebMvcConfigurer {
    private final AccountOwnershipInterceptor ownership;

    public ApiSecurityConfiguration(AccountOwnershipInterceptor ownership) {
        this.ownership = ownership;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ownership);
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            ObjectProvider<InternalApiTokenAuthorizationManager> internalApiToken) throws Exception {
        InternalApiTokenAuthorizationManager available = internalApiToken.getIfAvailable();
        AuthorizationManager<RequestAuthorizationContext> internalApiTokenCheck = available != null
                ? available
                : (authentication, context) -> new AuthorizationDecision(false);
        return http.csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/verify-email", "/api/auth/password-reset/request",
                                "/api/auth/password-reset/confirm", "/api/auth/email-verification/request",
                                "/api/auth/mfa/challenge")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/paypal")
                        .permitAll()
                        // PayPal 승인 후 브라우저 리다이렉트가 돌아오는 경로.
                        //
                        // checkout 형제 경로와 레거시/campaign 주문 경로가 모두 열려 있어야 한다.
                        // 주문 경로가 빠져 있으면 캠페인 주문(checkoutId == null) 구매자는 PayPal
                        // 승인을 마친 뒤 401 을 받는다. 인증은 token 파라미터와 provider 상태로
                        // 검증하므로 이 경로를 인증 없이 여는 것이 안전하다.
                        .requestMatchers(HttpMethod.GET, "/api/checkouts/*/payment/return",
                                "/api/checkouts/*/payment/cancel",
                                "/api/orders/*/payment/return",
                                "/api/orders/*/payment/cancel")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*",
                                "/api/products/*/reviews", "/api/campaigns/*", "/actuator/health",
                                "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        // 서비스 토큰으로만 연다. 기능이 꺼져 토큰 검사기가 없으면 아예 닫는다.
                        // 매처는 컨테이너가 디코딩·정규화한 경로로 판정하고, `;`·`//`·인코딩된 `/` 같은
                        // 모호한 경로는 그보다 앞서 StrictHttpFirewall 이 거절한다.
                        .requestMatchers("/api/internal/order-inventory/**")
                        .access(internalApiTokenCheck)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> { }))
                .headers(headers -> headers
                        .cacheControl(cache -> cache.disable())
                        // 아래 필터가 공개 읽기 응답에만 캐시 정책을 다시 붙인다.
                        .addHeaderWriter(new PublicReadCacheHeaderWriter()))
                .build();
    }
}