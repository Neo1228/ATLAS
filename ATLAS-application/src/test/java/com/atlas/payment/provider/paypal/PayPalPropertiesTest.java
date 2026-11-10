package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PayPalPropertiesTest {
    @Test
    void defaultsToSandboxEndpoint() {
        PayPalProperties properties = new PayPalProperties();

        assertThat(properties.getBaseUrl()).isEqualTo("https://api-m.sandbox.paypal.com");
        properties.validateBaseUrl();
    }

    @Test
    void rejectsArbitraryEndpointHost() {
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://attacker.example");

        assertThatThrownBy(properties::validateBaseUrl).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveEndpointRequiresExplicitOptIn() {
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://api-m.paypal.com");

        assertThatThrownBy(properties::validateBaseUrl).isInstanceOf(IllegalStateException.class);

    }
}
