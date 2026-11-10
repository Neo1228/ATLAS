package com.atlas.payment.provider.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "atlas.payment.paypal")
public class PayPalProperties {
    private String baseUrl = "https://api-m.sandbox.paypal.com";
    private String clientId;
    private String clientSecret;
    private String webhookId;
    private String returnUrl;
    private String cancelUrl;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getWebhookId() { return webhookId; }
    public void setWebhookId(String webhookId) { this.webhookId = webhookId; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }
    public boolean credentialsConfigured() {
        return configured(clientId) && configured(clientSecret);
    }

    public boolean webhookConfigured() {
        return configured(webhookId);
    }

    private static boolean configured(String value) {
        return StringUtils.hasText(value) && !"disabled".equalsIgnoreCase(value);
    }
}
