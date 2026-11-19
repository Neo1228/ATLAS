package com.atlas.application;

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
 * 운영자 계정 정지 API 의 보안 체인입니다.
 *
 * <p>일반 사용자 JWT 와 분리합니다. 정지는 권한이 큰 작업이고, 일반 사용자 토큰이
 * 탈취되어도 이 경로에 접근할 수 없어야 합니다. 기존 결제 복구 운영자 API 와 같은
 * 형태(환경변수 자격증명 + Basic 인증 + 별도 role)를 따릅니다.
 *
 * <p>비밀번호 최소 길이 32자를 요구하는 이유: 운영자 자격증명은 단일 비밀번호로
 * 강한 권한을 가지므로, 짧은 값을 허용하면 무차별 대입에 노출됩니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.account.suspension.operator.enabled", havingValue = "true")
public class AccountSuspensionOperatorSecurityConfiguration {
    private static final String PATH = "/api/internal/accounts/**";
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    @Bean
    @Order(2)
    SecurityFilterChain accountSuspensionOperatorFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${atlas.account.suspension.operator.username:}") String username,
            @Value("${atlas.account.suspension.operator.password:}") String password) throws Exception {
        if (username.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Account suspension operator requires a username and a password of at least 32 characters");
        }
        UserDetailsService users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("ACCOUNT_SUSPENSION_OPERATOR")
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);

        http.securityMatcher(PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ACCOUNT_SUSPENSION_OPERATOR"))
                .authenticationManager(new ProviderManager(provider))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
