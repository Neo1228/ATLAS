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
