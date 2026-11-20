package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 연결 실패(닫힌 포트)에서 모든 PayPal 호출이 provider 장애 예외로 감싸지는지 확인한다.
 * 감싸지 않은 호출은 서비스의 UNKNOWN 처리와 복구 경로를 건너뛰고 HTTP 500 으로 새어 나간다.
 * 실제 Sandbox 장애 주입(EXP-050)에서 승인 반영 중 주문 조회가 이렇게 실패했다.
 */
class PayPalRestClientFailureTest {
    private PayPalRestClient client;

    @BeforeEach
    void unreachableProvider() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PayPalProperties properties = new PayPalProperties();
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

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(PayPalRestClient.PayPalUnavailableException.class);
    }
}
