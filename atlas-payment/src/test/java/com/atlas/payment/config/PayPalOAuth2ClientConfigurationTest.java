package com.atlas.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.payment.provider.paypal.PayPalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class PayPalOAuth2ClientConfigurationTest {
    @Test
    void oauthScopeIsIsolatedFromBuyerRegistration() {
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://api-m.sandbox.paypal.com");
        properties.setClientId("client");
        properties.setClientSecret("secret");
        ClientRegistrationRepository registrations = new PayPalOAuth2ClientConfiguration()
                .paypalClientRegistrationRepository(properties);
        ClientRegistration buyer = registrations.findByRegistrationId("paypal");
        ClientRegistration payouts = registrations.findByRegistrationId("paypal-payouts");
        assertThat(buyer.getScopes()).doesNotContain("https://uri.paypal.com/services/payments/payouts");
        assertThat(payouts.getScopes()).containsExactly("https://uri.paypal.com/services/payments/payouts");
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
