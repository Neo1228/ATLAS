package com.atlas.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Seller 지급 프로필 검증과 지급 승인·수동 처리 경로의 인증 경계.
 *
 * <p>전제(ADR-0078): 설정된 단일 운영자 자격 증명이 유일한 관문이다. 사람별 신원, MFA, 운영자 간 maker-checker는
 * 이 코드에 없다. 감사 행의 actor는 이 자격 증명의 username이므로 운영자 둘을 구분하지 못한다. Sandbox 전용이며,
 * Live 지급 전에는 IdP나 gateway가 사람별 신원과 MFA를 맡고 principal 이름으로 넘겨야 한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
public class SettlementPayoutOperatorSecurityConfiguration {
    private static final String[] PATHS = {
        "/api/internal/settlement/payouts/**",
        "/api/internal/settlement/payout-profiles/**"
    };
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    @Bean
    @Order(1)
    SecurityFilterChain settlementPayoutOperatorFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${atlas.settlement.payout.operator.username:}") String username,
            @Value("${atlas.settlement.payout.operator.password:}") String password) throws Exception {
        if (username.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Settlement payout operator requires a username and a password of at least 32 characters");
        }
        UserDetailsService users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("SETTLEMENT_PAYOUT_OPERATOR")
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);

        http.securityMatcher(PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("SETTLEMENT_PAYOUT_OPERATOR"))
                .authenticationManager(new ProviderManager(provider))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
