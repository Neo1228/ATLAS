package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.dto.response.PaymentRefundProviderResponse;
import com.atlas.payment.provider.PaymentOperation;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * PayPal이 처리 대상이 아닌 상태 값을 보냈을 때 결제를 확정하지 않고 UNKNOWN으로 남기는지 확인한다.
 *
 * <p>HTTP 응답 본문부터 provider 결과까지 실제 클라이언트로 지나가므로, 상태를 내부에서 어떤 타입으로
 * 다루는지와 무관하게 같은 기대값이 유지되어야 한다.
 */
class PayPalPaymentProviderStatusTest {
    private static final String ORDER_AMOUNT =
            "\"purchase_units\":[{\"amount\":{\"currency_code\":\"USD\",\"value\":\"10.00\"}}]";
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private HttpServer server;
    private PayPalPaymentProvider provider;

    @BeforeEach
    void startPayPal() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            byte[] body = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl(url);
        properties.setCaptureBaseUrl(url);
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setLocalProxyEnabled(true);
        properties.validateBaseUrl();
        OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
        when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(300)));
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorized);
        provider = new PayPalPaymentProvider(new PayPalRestClient(RestClient.builder(), properties, manager,
                new ObjectMapper(), Duration.ofMillis(500), Duration.ofSeconds(1)));
    }

    @AfterEach
    void stopPayPal() {
        server.stop(0);
    }

    @Test
    void statusesOutsideTheHandledSetLeaveThePaymentUnknown() {
        responses.put("GET /v2/checkout/orders/ORDER-1",
                "{\"id\":\"ORDER-1\",\"status\":\"NEW_ORDER_STATUS\"," + ORDER_AMOUNT + "}");
        assertThat(provider.completeApproval(request(PaymentOperation.AUTHORIZATION, null), "ORDER-1").outcome())
                .isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);

        // 대소문자가 다른 값도 PayPal 계약 값이 아니므로 승인으로 보지 않는다.
        responses.put("GET /v2/checkout/orders/ORDER-1",
                "{\"id\":\"ORDER-1\",\"status\":\"approved\"," + ORDER_AMOUNT + "}");
        assertThat(provider.perform(request(PaymentOperation.AUTHORIZATION, null)).outcome())
                .isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);

        responses.put("GET /v2/checkout/orders/ORDER-1",
                "{\"id\":\"ORDER-1\",\"status\":\"APPROVED\"," + ORDER_AMOUNT + "}");
        responses.put("POST /v2/checkout/orders/ORDER-1/authorize",
                "{\"id\":\"AUTH-1\",\"status\":\"NEW_AUTHORIZATION_STATUS\"}");
        assertThat(provider.perform(request(PaymentOperation.AUTHORIZATION, null)).outcome())
                .isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);

        // capture 요청 응답은 COMPLETED와 PENDING만 확정하고, 거절도 UNKNOWN으로 남긴다.
        responses.put("POST /v2/payments/authorizations/AUTH-1/capture",
                "{\"id\":\"CAPTURE-1\",\"status\":\"DECLINED\","
                        + "\"amount\":{\"currency_code\":\"USD\",\"value\":\"10.00\"}}");
        assertThat(provider.perform(request(PaymentOperation.CAPTURE, "AUTH-1")).outcome())
                .isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);

        responses.put("GET /v2/payments/refunds/REFUND-1", "{\"id\":\"REFUND-1\",\"status\":\"NEW_REFUND_STATUS\"}");
        PaymentRefundProviderResponse refund = provider.getRefund("REFUND-1");
        assertThat(refund.outcome()).isEqualTo(PaymentRefundProviderResponse.Outcome.UNKNOWN);
        assertThat(refund.providerStatus()).isEqualTo("NEW_REFUND_STATUS");
    }

    @Test
    void authorizationResponseWithoutStatusIsUnknown() {
        responses.put("GET /v2/checkout/orders/ORDER-1",
                "{\"id\":\"ORDER-1\",\"status\":\"APPROVED\"," + ORDER_AMOUNT + "}");
        responses.put("POST /v2/checkout/orders/ORDER-1/authorize", "{\"id\":\"AUTH-1\"}");

        PaymentProviderResponse result = provider.perform(request(PaymentOperation.AUTHORIZATION, null));

        assertThat(result.outcome()).isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);
    }

    private static PaymentRequest request(PaymentOperation operation, String authorizationId) {
        return new PaymentRequest(operation, UUID.randomUUID(), UUID.randomUUID(), 1000L, "USD", "ORDER-1",
                authorizationId, "create-1", "auth-1", "capture-1", "void-1", "return", "cancel");
    }
}
