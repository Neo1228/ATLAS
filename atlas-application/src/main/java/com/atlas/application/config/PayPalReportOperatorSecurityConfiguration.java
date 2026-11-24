package com.atlas.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.settlement.report.operator.enabled", havingValue = "true")
public class PayPalReportOperatorSecurityConfiguration {
    private static final String REPORT_PATH = "/api/internal/settlement/paypal/balance-report/**";
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    @Bean
    @Order(1)
    SecurityFilterChain payPalReportOperatorFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(REPORT_PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("SETTLEMENT_REPORT_OPERATOR"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    UserDetailsService payPalReportOperatorUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${atlas.settlement.report.operator.username:}") String username,
            @Value("${atlas.settlement.report.operator.password:}") String password) {
        if (username.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Report operator requires a username and a password with at least 32 characters");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("SETTLEMENT_REPORT_OPERATOR")
                .build());
    }
}
