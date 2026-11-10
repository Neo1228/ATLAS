package com.atlas.payment.provider.paypal;

import java.net.URI;
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
    public void validateBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) throw new IllegalStateException("PayPal base URL is required");
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PayPal base URL is invalid", exception);
        }
        String host = endpoint.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        boolean sandbox = "api-m.sandbox.paypal.com".equalsIgnoreCase(host);
        String path = endpoint.getPath();
        if ((!local && !sandbox)
                || (path != null && !path.isBlank() && !"/".equals(path))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("PayPal base URL must use an approved PayPal or local test endpoint");
        }
        if (sandbox && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("PayPal endpoints must use HTTPS");
        }
    }

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
