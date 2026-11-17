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

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.payment.recovery.operator.enabled", havingValue = "true")
public class PaymentRecoveryOperatorSecurityConfiguration {
    private static final String PATH = "/api/internal/payment/recovery/**";
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    @Bean
    @Order(1)
    SecurityFilterChain paymentRecoveryOperatorFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${atlas.payment.recovery.operator.username:}") String username,
            @Value("${atlas.payment.recovery.operator.password:}") String password) throws Exception {
        if (username.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Payment recovery operator requires a username and a password of at least 32 characters");
        }
        UserDetailsService users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("PAYMENT_RECOVERY_OPERATOR")
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);

        http.securityMatcher(PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("PAYMENT_RECOVERY_OPERATOR"))
                .authenticationManager(new ProviderManager(provider))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
