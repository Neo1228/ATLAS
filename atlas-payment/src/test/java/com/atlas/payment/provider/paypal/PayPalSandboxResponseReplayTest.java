package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 PayPal Sandbox 가 준 응답(식별자와 개인 정보를 치환한 기록, EXP-081)을 그대로 재생해
 * 운영 파서가 읽는 필드와 상태를 고정한다. 통합 테스트의 MockWebServer 대역은 우리가 만든 응답이라
 * PayPal 이 모양을 바꿔도 통과하지만, 이 기록은 실제 응답이다.
 */
class PayPalSandboxResponseReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode RECORDED = recorded();
    private static final String ORDER_ID = "5O190127TN364715T";
    private static final String AUTHORIZATION_ID = "3C679366HH908993F";
    private static final String CAPTURE_ID = "2GG279541U471931P";
    private static final String REFUND_ID = "1JU08902781691411";

    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> preferHeaders = new ArrayList<>();
    private HttpServer server;
    private PayPalRestClient client;

    @BeforeEach
    void startRecordedPayPal() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            preferHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Prefer")));
            Reply current = reply.get();
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setWebhookId("local-webhook-id");
        properties.setLocalProxyEnabled(true);
        properties.validateBaseUrl();
        OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
        when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(300)));
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorized);
        client = new PayPalRestClient(RestClient.builder(), properties, manager, JSON,
                Duration.ofMillis(500), Duration.ofSeconds(2));
    }

    @AfterEach
    void stopRecordedPayPal() {
        server.stop(0);
    }

    @Test
    void orderCreatedWithPaymentSourceAsksForPayerAction() {
        replyWith("createOrderPayerAction");

        PayPalRestClient.OrderResult order = client.createOrder("buyer-1", new BigDecimal("1.00"), "USD",
                "https://example.com/return", "https://example.com/cancel", "create-request-1");

        assertThat(order.id()).isEqualTo(ORDER_ID);
        assertThat(order.status()).isEqualTo(PayPalOrderStatus.PAYER_ACTION_REQUIRED);
        assertThat(order.approvalUrl()).isNotNull();
    }

    @Test
    void orderLookupFollowsApprovalAuthorizationCaptureAndRefund() {
        replyWith("getOrderApproved");
        PayPalRestClient.OrderStatus approved = client.getOrder(ORDER_ID);
        assertThat(approved.status()).isEqualTo(PayPalOrderStatus.APPROVED);
        assertThat(approved.amount()).isEqualByComparingTo("1.00");
        assertThat(approved.currency()).isEqualTo("USD");
        assertThat(approved.authorizationId()).isNull();
        assertThat(approved.captureId()).isNull();

        replyWith("getOrderAuthorized");
        PayPalRestClient.OrderStatus authorized = client.getOrder(ORDER_ID);
        assertThat(authorized.status()).isEqualTo(PayPalOrderStatus.COMPLETED);
        assertThat(authorized.authorizationId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(authorized.authorizationStatus()).isEqualTo(PayPalAuthorizationStatus.CREATED);
        assertThat(authorized.captureId()).isNull();

        replyWith("getOrderCaptured");
        PayPalRestClient.OrderStatus captured = client.getOrder(ORDER_ID);
        // 실제 PayPal 은 캡처 뒤에도 승인 항목을 CAPTURED 상태로 함께 준다.
        assertThat(captured.authorizationStatus()).isEqualTo(PayPalAuthorizationStatus.CAPTURED);
        assertThat(captured.captureId()).isEqualTo(CAPTURE_ID);
        assertThat(captured.captureStatus()).isEqualTo(PayPalCaptureStatus.COMPLETED);
        assertThat(captured.captureAmount()).isEqualByComparingTo("1.00");
        assertThat(captured.captureCurrency()).isEqualTo("USD");

        replyWith("getOrderPartiallyRefunded");
        assertThat(client.getOrder(ORDER_ID).captureStatus()).isEqualTo(PayPalCaptureStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void authorizeCaptureAndRefundResponsesAreParsed() {
        replyWith("authorizeOrder");
        PayPalRestClient.AuthorizationResult authorization = client.authorizeOrder(ORDER_ID, "authorize-request-1");
        assertThat(authorization.id()).isEqualTo(AUTHORIZATION_ID);
        assertThat(authorization.status()).isEqualTo(PayPalAuthorizationStatus.CREATED);

        replyWith("captureRepresentation");
        PayPalRestClient.CaptureResult capture = client.captureAuthorization(AUTHORIZATION_ID, new BigDecimal("1.00"),
                "USD", "capture-request-1");
        assertThat(capture.id()).isEqualTo(CAPTURE_ID);
        assertThat(capture.status()).isEqualTo(PayPalCaptureStatus.COMPLETED);
        assertThat(capture.amount()).isEqualByComparingTo("1.00");
        assertThat(preferHeaders.getLast()).isEqualTo("return=representation");

        // Prefer 가 없을 때의 실제 기본 응답은 id, status, links 뿐이라 금액이 없다.
        replyWith("captureMinimal");
        PayPalRestClient.CaptureResult minimal = client.captureAuthorization(AUTHORIZATION_ID, new BigDecimal("1.00"),
                "USD", "capture-request-2");
        assertThat(minimal.id()).isEqualTo(CAPTURE_ID);
        assertThat(minimal.amount()).isNull();

        replyWith("refundRepresentation");
        PayPalRestClient.RefundResult refund = client.refundCapture(CAPTURE_ID, new BigDecimal("0.40"), "USD",
                "refund-request-1");
        assertThat(refund.id()).isEqualTo(REFUND_ID);
        assertThat(refund.status()).isEqualTo(PayPalRefundStatus.COMPLETED);
        assertThat(refund.amount()).isEqualByComparingTo("0.40");

        replyWith("getRefund");
        assertThat(client.getRefund(REFUND_ID).id()).isEqualTo(REFUND_ID);
        replyWith("refundMinimal");
        assertThat(client.refundCapture(CAPTURE_ID, null, "USD", "refund-request-2").amount()).isNull();
    }

    @Test
    void forgedWebhookSignatureIsNotVerifiedWhenPayPalAnswersFailure() {
        replyWith("verifyWebhookFailure");

        assertThat(client.verifyWebhook(signature("signature-1"), "{\"id\":\"WH-1\"}").verified()).isFalse();
    }

    /**
     * 실제 Sandbox 가 준 거절 본문을 재생한다. 404/422/400 은 모두 장애 예외가 되어 결제 서비스가
     * UNKNOWN 으로 두고 같은 Request-Id 로 복구한다(성공이나 거절로 단정하지 않는다).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("definitiveRejections")
    void recordedRejectionsAreProviderUnavailable(String fixture, Consumer<PayPalRestClient> call) {
        replyWith(fixture);

        assertThatThrownBy(() -> call.accept(client)).isInstanceOf(PayPalRestClient.PayPalUnavailableException.class);
    }

    /**
     * 4xx 는 상태와 PayPal 이슈를 담은 거절 예외가 되고(실제 Sandbox 본문), 이슈를 읽지 못하는 본문이나 5xx 는
     * 확정 판단에 쓰이지 않는다. 어느 쪽이든 장애 예외의 하위 형식이라 분류하지 않는 호출부는 UNKNOWN 으로 둔다.
     */
    @Test
    void rejectionCarriesStatusAndIssueOnlyForPayPalShapedClientErrors() {
        replyWith("error422AuthorizationAlreadyCaptured");
        assertThatThrownBy(() -> client.captureAuthorization(AUTHORIZATION_ID, new BigDecimal("1.00"), "USD",
                "capture-request-1"))
                .isInstanceOfSatisfying(PayPalRestClient.PayPalRejectedException.class,
                        rejection -> assertThat(rejection.is(422, "AUTHORIZATION_ALREADY_CAPTURED")).isTrue());

        reply.set(new Reply(400, "<html>Bad Request</html>"));
        assertThatThrownBy(() -> client.authorizeOrder(ORDER_ID, "authorize-request-1"))
                .isInstanceOfSatisfying(PayPalRestClient.PayPalRejectedException.class,
                        rejection -> assertThat(rejection.issue()).isNull());

        reply.set(new Reply(503, "{\"details\":[{\"issue\":\"AUTHORIZATION_VOIDED\"}]}"));
        assertThatThrownBy(() -> client.authorizeOrder(ORDER_ID, "authorize-request-1"))
                .isInstanceOf(PayPalRestClient.PayPalUnavailableException.class)
                .isNotInstanceOf(PayPalRestClient.PayPalRejectedException.class);
    }

    static Stream<Arguments> definitiveRejections() {
        PaymentWebhookSignatureRequest nonBlank = signature("signature-1");
        return Stream.of(
                Arguments.of("error404UnknownResource", (Consumer<PayPalRestClient>) c -> c.getOrder(ORDER_ID)),
                Arguments.of("error422OrderNotApproved",
                        (Consumer<PayPalRestClient>) c -> c.authorizeOrder(ORDER_ID, "authorize-request-1")),
                Arguments.of("error422AuthorizationAlreadyCaptured",
                        (Consumer<PayPalRestClient>) c -> c.captureAuthorization(AUTHORIZATION_ID,
                                new BigDecimal("1.00"), "USD", "capture-request-1")),
                Arguments.of("error422CaptureFullyRefunded",
                        (Consumer<PayPalRestClient>) c -> c.refundCapture(CAPTURE_ID, null, "USD", "refund-request-1")),
                Arguments.of("error404UnknownResource", (Consumer<PayPalRestClient>) c -> c.getRefund(REFUND_ID)),
                Arguments.of("error400WebhookNullSignature",
                        (Consumer<PayPalRestClient>) c -> c.verifyWebhook(nonBlank, "{\"id\":\"WH-1\"}")));
    }

    private static PaymentWebhookSignatureRequest signature(String transmissionSignature) {
        return new PaymentWebhookSignatureRequest("SHA256withRSA",
                "https://api-m.sandbox.paypal.com/v1/notifications/certs/CERT-1", "transmission-1",
                transmissionSignature, "2026-11-25T00:00:00Z");
    }

    private void replyWith(String fixture) {
        JsonNode recorded = RECORDED.get(fixture);
        reply.set(new Reply(recorded.get("status").asInt(), recorded.get("body").toString()));
    }

    private static JsonNode recorded() {
        try (InputStream input = PayPalSandboxResponseReplayTest.class
                .getResourceAsStream("/paypal-sandbox/responses.json")) {
            return JSON.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("recorded PayPal Sandbox responses are missing", exception);
        }
    }

    private record Reply(int status, String body) { }
}
