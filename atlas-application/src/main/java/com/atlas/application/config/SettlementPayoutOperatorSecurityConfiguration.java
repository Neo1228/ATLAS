package com.atlas.application.config;

import com.atlas.account.service.AccountService;
import com.atlas.application.security.PayoutOperatorAuthenticationConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Seller 지급 프로필 검증, 지급 승인·수동 처리, 결과 불명 환불의 운영자 확정 경로의 인증 경계.
 *
 * <p>환불 확정({@code /api/internal/payment/refunds/**}, ADR-0108)도 판매자 원장을 움직이는 운영자 행위라 지급
 * 운영자와 같은 사람·같은 권한 목록({@code payout_operator_account})·같은 MFA 요구를 쓴다. 지급 provider 검토 해소
 * ({@code resolve-provider-review})와 같이 provider 증거 하나로 한 사람이 닫는 단계다.
 *
 * <p>신원 모드는 {@code atlas.settlement.payout.operator.identity}로 고른다(ADR-0092).
 * <ul>
 *   <li>{@code account}(기본): 운영자는 사람별 account다. 기존 로그인(PBKDF2 해시, 로그인 한도, TOTP MFA)으로 받은
 *       access JWT로 인증하고, 요청마다 {@code payout_operator_account} 권한·계정 상태·MFA 등록과 세션의 2차 인증 표식을 DB에서 확인한다.
 *       감사 actor는 account id다. 같은 사람이 프로필 검증과 지급 승인을 모두 하는 것은 DB trigger가 거절한다.</li>
 *   <li>{@code shared-credential}(rollback용): 기존 HTTP Basic 단일 자격 증명(ADR-0078). 사람별 신원이 없어
 *       감사 행의 actor가 운영자 모두에게 같은 문자열이고 승인자 ≠ 검증자 규칙이 적용되지 않는다. 사람 신원이 아닌
 *       actor에는 trigger가 적용되지 않으므로 이 모드의 기존 흐름은 막히지 않는다.</li>
 * </ul>
 *
 * <p>전제: {@code account} 모드에서 principal 이름(account id)이 사람 한 명을 가리킨다는 것은 운영자 계정을
 * 한 사람이 쓴다는 운영 절차에 기대고, 앱은 이를 검사할 수 없다. 계정 잠금(영구)·자격 증명 회전 정책·행동별 재인증은
 * 이 코드에 없고 그 이유는 ADR-0094에 있다. IdP나 gateway가 신원을 맡게 되면 principal 이름만 같은 의미로 넘기면 된다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
public class SettlementPayoutOperatorSecurityConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementPayoutOperatorSecurityConfiguration.class);
    private static final String[] PATHS = {
        "/api/internal/settlement/payouts/**",
        "/api/internal/settlement/payout-profiles/**",
        "/api/internal/payment/refunds/**"
    };
    private static final String ROLE = "SETTLEMENT_PAYOUT_OPERATOR";
    private static final int MINIMUM_PASSWORD_LENGTH = 32;

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "atlas.settlement.payout.operator.identity", havingValue = "account",
            matchIfMissing = true)
    SecurityFilterChain settlementPayoutOperatorAccountFilterChain(
            HttpSecurity http, JwtDecoder jwtDecoder, AccountService accounts) throws Exception {
        http.securityMatcher(PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(ROLE))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(new PayoutOperatorAuthenticationConverter(accounts, ROLE))));
        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "atlas.settlement.payout.operator.identity", havingValue = "shared-credential")
    SecurityFilterChain settlementPayoutOperatorFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${atlas.settlement.payout.operator.username:}") String username,
            @Value("${atlas.settlement.payout.operator.password:}") String password) throws Exception {
        if (username.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Settlement payout operator requires a username and a password of at least 32 characters");
        }
        LOGGER.warn("지급 운영자가 단일 공유 자격 증명 모드입니다. 감사 actor가 사람을 구분하지 못하고 승인자 분리 규칙이 적용되지 않습니다.");
        UserDetailsService users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles(ROLE)
                .build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);

        http.securityMatcher(PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(ROLE))
                .authenticationManager(new ProviderManager(provider))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
