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
    void captureBaseUrlDefaultsToThePayPalApiBase() {
        PayPalProperties properties = new PayPalProperties();

        assertThat(properties.getCaptureBaseUrl()).isEqualTo(properties.getBaseUrl());
    }

    @Test
    void nonstandardPayPalPortsRequireExplicitLoopbackProxyOptIn() {
        PayPalProperties apiBase = new PayPalProperties();
        apiBase.setBaseUrl("https://api-m.sandbox.paypal.com:19443");
        assertThatThrownBy(apiBase::validateBaseUrl).isInstanceOf(IllegalStateException.class);

        PayPalProperties captureBase = new PayPalProperties();
        captureBase.setCaptureBaseUrl("https://api-m.sandbox.paypal.com:19444");
        assertThatThrownBy(captureBase::validateBaseUrl).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void captureProxyMayUseAnotherLoopbackPortWhenExplicitlyEnabled() {
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://127.0.0.1:19443");
        properties.setCaptureBaseUrl("https://127.0.0.1:19444");
        properties.setLocalProxyEnabled(true);

        properties.validateBaseUrl();

        assertThat(properties.getCaptureBaseUrl()).isEqualTo("https://127.0.0.1:19444");
    }

    @Test
    void captureBaseUrlCannotChangePayPalHostOrScheme() {
        PayPalProperties differentHost = new PayPalProperties();
        differentHost.setCaptureBaseUrl("https://attacker.example");
        assertThatThrownBy(differentHost::validateBaseUrl).isInstanceOf(IllegalStateException.class);

        PayPalProperties differentScheme = new PayPalProperties();
        differentScheme.setCaptureBaseUrl("http://api-m.sandbox.paypal.com:19444");
        assertThatThrownBy(differentScheme::validateBaseUrl).isInstanceOf(IllegalStateException.class);
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
