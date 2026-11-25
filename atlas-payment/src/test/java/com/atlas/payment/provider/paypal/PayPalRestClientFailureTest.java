package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 연결 실패(닫힌 포트)에서 모든 PayPal 호출이 provider 장애 예외로 감싸지는지 확인한다.
 * 감싸지 않은 호출은 서비스의 UNKNOWN 처리와 복구 경로를 건너뛰고 HTTP 500 으로 새어 나간다.
 * 실제 Sandbox 장애 주입(EXP-050)에서 승인 반영 중 주문 조회가 이렇게 실패했다.
 */
class PayPalRestClientFailureTest {
    private PayPalRestClient client;
    private PayPalProperties properties;

    @BeforeEach
    void unreachableProvider() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties = new PayPalProperties();
        properties.setBaseUrl("http://127.0.0.1:" + closedPort);
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
        when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(300)));
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorized);
        client = new PayPalRestClient(RestClient.builder(), properties, manager, new ObjectMapper(),
                Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @Test
    void orderLookupFailureIsProviderUnavailable() {
        assertUnavailable(() -> client.getOrder("ORDER-1"));
    }

    @Test
    void authorizationVoidFailureIsProviderUnavailable() {
        assertUnavailable(() -> client.voidAuthorization("AUTH-1", "void-request-1"));
    }

    @Test
    void writeAndRefundCallsAreProviderUnavailable() {
        assertUnavailable(() -> client.authorizeOrder("ORDER-1", "authorize-request-1"));
        assertUnavailable(() -> client.captureAuthorization("AUTH-1", new BigDecimal("1.00"), "USD",
                "capture-request-1"));
        assertUnavailable(() -> client.refundCapture("CAPTURE-1", new BigDecimal("1.00"), "USD",
                "refund-request-1"));
        assertUnavailable(() -> client.getRefund("REFUND-1"));
    }

    /**
     * 토큰 발급 실패는 RestClientException 이 아니라 ClientAuthorizationException 으로 올라온다.
     * 감싸지 않으면 결제 서비스의 UNKNOWN 처리를 건너뛰고 HTTP 500 으로 샌다. 잘못된 client
     * 자격 증명으로 실제 Sandbox 토큰 endpoint 를 호출해 같은 예외를 확인했다(EXP-081).
     */
    @Test
    void oauthTokenFailureIsProviderUnavailable() {
        OAuth2AuthorizedClientManager failing = mock(OAuth2AuthorizedClientManager.class);
        when(failing.authorize(any(OAuth2AuthorizeRequest.class))).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_token_response", "Client Authentication failed", null), "paypal"));
        PayPalRestClient oauthFailing = new PayPalRestClient(RestClient.builder(), properties, failing,
                new ObjectMapper(), Duration.ofMillis(500), Duration.ofSeconds(1));

        assertUnavailable(() -> oauthFailing.getOrder("ORDER-1"));
        assertUnavailable(() -> oauthFailing.captureAuthorization("AUTH-1", new BigDecimal("1.00"), "USD",
                "capture-request-1"));
    }

    /**
     * 서명 헤더가 비면 실제 PayPal 은 200 FAILURE 가 아니라 400 을 준다. 닫힌 포트로 연결하므로
     * PayPal 을 호출했다면 장애 예외가 났을 것이다. 호출 없이 검증 실패로 끝나야 한다.
     */
    @Test
    void blankWebhookSignatureIsRejectedWithoutCallingPayPal() {
        properties.setWebhookId("local-webhook-id");
        PaymentWebhookSignatureRequest blankSignature = new PaymentWebhookSignatureRequest("SHA256withRSA",
                "https://api-m.sandbox.paypal.com/v1/notifications/certs/CERT-1", "transmission-1", " ",
                "2026-11-25T00:00:00Z");

        assertThat(client.verifyWebhook(blankSignature, "{\"id\":\"WH-1\"}").verified()).isFalse();
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(PayPalRestClient.PayPalUnavailableException.class);
    }
}
