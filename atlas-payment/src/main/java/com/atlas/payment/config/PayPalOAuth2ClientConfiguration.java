package com.atlas.payment.config;

import com.atlas.payment.provider.paypal.PayPalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
@ConditionalOnExpression(
        "'${atlas.payment.provider:disabled}' == 'paypal' || '${atlas.payment.paypal.payouts.enabled:false}' == 'true'")
public class PayPalOAuth2ClientConfiguration {
    @Bean
    ClientRegistrationRepository paypalClientRegistrationRepository(PayPalProperties properties) {
        if (!properties.credentialsConfigured()) {
            throw new IllegalStateException("PayPal provider requires PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET");
        }
        properties.validateBaseUrl();
        String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        ClientRegistration registration = ClientRegistration.withRegistrationId("paypal")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(baseUrl + "/v1/oauth2/token")
                .build();
        // scope를 요청하지 않는다. 실제 Sandbox는 payouts URI를 scope로 요청하면 토큰의 scope를 "openid"로만 줄이고
        // 이 토큰으로 지급을 만들면 403 NOT_AUTHORIZED다(EXP-102). Payouts 권한은 앱 설정이 주며 scope 요청으로 좁히지 않는다.
        ClientRegistration payoutsRegistration = ClientRegistration.withRegistrationId("paypal-payouts")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(baseUrl + "/v1/oauth2/token")
                .build();
        return new InMemoryClientRegistrationRepository(registration, payoutsRegistration);
    }

    @Bean
    OAuth2AuthorizedClientManager paypalAuthorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClientService) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials().build());
        return manager;
    }
}
