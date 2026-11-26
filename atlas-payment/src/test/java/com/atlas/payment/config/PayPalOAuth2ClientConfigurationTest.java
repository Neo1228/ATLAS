package com.atlas.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.provider.paypal.PayPalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class PayPalOAuth2ClientConfigurationTest {
    @Test
    void payoutsRegistrationIsSeparateAndRequestsNoScope() {
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://api-m.sandbox.paypal.com");
        properties.setClientId("client");
        properties.setClientSecret("secret");
        ClientRegistrationRepository registrations = new PayPalOAuth2ClientConfiguration()
                .paypalClientRegistrationRepository(properties);
        ClientRegistration buyer = registrations.findByRegistrationId("paypal");
        ClientRegistration payouts = registrations.findByRegistrationId("paypal-payouts");
        assertThat(buyer.getScopes()).isNullOrEmpty();
        // 실제 Sandbox는 payouts URI scope 요청을 "openid" 토큰으로 낮추고 지급 생성을 403으로 거절한다(EXP-102).
        assertThat(payouts.getScopes()).isNullOrEmpty();
        assertThat(payouts.getRegistrationId()).isNotEqualTo(buyer.getRegistrationId());
    }

    @Test
    void startupRejectsDisabledSentinelAndBlankCredentials() {
        for (String[] pair : new String[][] {
                {"disabled", "secret"}, {"client", "DISABLED"}, {"", "secret"}, {"client", " "}}) {
            PayPalProperties properties = new PayPalProperties();
            properties.setBaseUrl("https://api-m.sandbox.paypal.com");
            properties.setClientId(pair[0]);
            properties.setClientSecret(pair[1]);

            assertThatThrownBy(() -> new PayPalOAuth2ClientConfiguration()
                    .paypalClientRegistrationRepository(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAYPAL_CLIENT_ID");
        }
    }
}
