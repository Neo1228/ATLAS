package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class PayPalRestClientRoutingTest {

    @Test
    void captureUsesDedicatedBaseUrlWhileOrderLookupAndAuthorizationUseApiBaseUrl() throws IOException {
        List<String> apiRequests = new ArrayList<>();
        List<String> captureRequests = new ArrayList<>();
        List<Integer> apiConnectionPorts = new ArrayList<>();
        List<Integer> captureConnectionPorts = new ArrayList<>();
        HttpServer apiServer = startServer(apiRequests, apiConnectionPorts, path -> path.endsWith("/authorize")
                ? "{\"id\":\"AUTH-1\",\"status\":\"CREATED\"}"
                : "{\"id\":\"ORDER-1\",\"status\":\"APPROVED\",\"purchase_units\":["
                        + "{\"amount\":{\"currency_code\":\"USD\",\"value\":\"1.00\"}}]}");
        HttpServer captureServer = startServer(captureRequests, captureConnectionPorts,
                path -> "{\"id\":\"CAPTURE-1\",\"status\":\"COMPLETED\","
                        + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"1.00\"}}");
        try {
            PayPalProperties properties = new PayPalProperties();
            properties.setBaseUrl(localUrl(apiServer));
            properties.setCaptureBaseUrl(localUrl(captureServer));
            properties.setClientId("test-client");
            properties.setClientSecret("test-secret");
            properties.setLocalProxyEnabled(true);
            properties.validateBaseUrl();

            OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
            when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                    "test-token", Instant.now(), Instant.now().plusSeconds(300)));
            OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
            when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorized);
            PayPalRestClient client = new PayPalRestClient(RestClient.builder(), properties, manager,
                    new ObjectMapper(), Duration.ofMillis(500), Duration.ofSeconds(1));

            client.getOrder("ORDER-1");
            client.authorizeOrder("ORDER-1", "authorize-request-1");
            client.captureAuthorization("AUTH-1", new BigDecimal("1.00"), "USD", "capture-request-1");
            client.captureAuthorization("AUTH-2", new BigDecimal("1.00"), "USD", "capture-request-2");

            assertThat(apiRequests).containsExactly(
                    "GET /v2/checkout/orders/ORDER-1",
                    "POST /v2/checkout/orders/ORDER-1/authorize");
            assertThat(apiConnectionPorts).hasSize(2);
            assertThat(apiConnectionPorts.get(0)).isEqualTo(apiConnectionPorts.get(1));
            assertThat(captureRequests).containsExactly(
                    "POST /v2/payments/authorizations/AUTH-1/capture",
                    "POST /v2/payments/authorizations/AUTH-2/capture");
            assertThat(captureConnectionPorts).hasSize(2);
            assertThat(captureConnectionPorts.get(0)).isEqualTo(captureConnectionPorts.get(1));
        } finally {
            apiServer.stop(0);
            captureServer.stop(0);
        }
    }

    private static HttpServer startServer(List<String> requests, List<Integer> connectionPorts,
            Function<String, String> responseForPath)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            connectionPorts.add(exchange.getRemoteAddress().getPort());
            byte[] body = responseForPath.apply(path).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String localUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
