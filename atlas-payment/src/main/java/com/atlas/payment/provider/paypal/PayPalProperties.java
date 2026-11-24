package com.atlas.payment.provider.paypal;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "atlas.payment.paypal")
public class PayPalProperties {
    private String baseUrl = "https://api-m.sandbox.paypal.com";
    private String captureBaseUrl;
    private boolean localProxyEnabled;
    private String clientId;
    private String clientSecret;
    private String webhookId;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCaptureBaseUrl() {
        return StringUtils.hasText(captureBaseUrl) ? captureBaseUrl : baseUrl;
    }
    public void setCaptureBaseUrl(String captureBaseUrl) { this.captureBaseUrl = captureBaseUrl; }
    public boolean isLocalProxyEnabled() { return localProxyEnabled; }
    public void setLocalProxyEnabled(boolean localProxyEnabled) { this.localProxyEnabled = localProxyEnabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getWebhookId() { return webhookId; }
    public void setWebhookId(String webhookId) { this.webhookId = webhookId; }
    public void validateBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) throw new IllegalStateException("PayPal base URL is required");
        URI endpoint;
        try {
            endpoint = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PayPal base URL is invalid", exception);
        }
        String host = endpoint.getHost();
        boolean local = isLocalHost(host);
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
        validateProxyEndpoint(endpoint);
        if (StringUtils.hasText(captureBaseUrl)) {
            URI captureEndpoint;
            try {
                captureEndpoint = URI.create(captureBaseUrl);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("PayPal capture URL is invalid", exception);
            }
            String capturePath = captureEndpoint.getPath();
            if (!endpoint.getHost().equalsIgnoreCase(captureEndpoint.getHost())
                    || !endpoint.getScheme().equalsIgnoreCase(captureEndpoint.getScheme())
                    || (capturePath != null && !capturePath.isBlank() && !"/".equals(capturePath))
                    || captureEndpoint.getUserInfo() != null || captureEndpoint.getQuery() != null
                    || captureEndpoint.getFragment() != null) {
                throw new IllegalStateException("PayPal capture URL must share the approved API host and scheme");
            }
            validateProxyEndpoint(captureEndpoint);
        }
    }

    private void validateProxyEndpoint(URI endpoint) {
        int defaultPort = defaultPort(endpoint.getScheme());
        int configuredPort = endpoint.getPort() == -1 ? defaultPort : endpoint.getPort();
        if (defaultPort == -1 || configuredPort < 1 || configuredPort > 65_535) {
            throw new IllegalStateException("PayPal endpoint must use HTTP or HTTPS with a valid port");
        }
        if (configuredPort != defaultPort
                && (!localProxyEnabled || !resolvesOnlyToLoopback(endpoint.getHost()))) {
            throw new IllegalStateException("Nonstandard PayPal ports require an explicitly enabled loopback proxy");
        }
    }

    private static int defaultPort(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) return 443;
        if ("http".equalsIgnoreCase(scheme)) return 80;
        return -1;
    }

    private static boolean isLocalHost(String host) {
        String normalizedHost = host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return "localhost".equalsIgnoreCase(normalizedHost)
                || "127.0.0.1".equals(normalizedHost) || "::1".equals(normalizedHost);
    }

    private static boolean resolvesOnlyToLoopback(String host) {
        try {
            String normalizedHost = host != null && host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) return false;
            }
            return true;
        } catch (UnknownHostException exception) {
            return false;
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
