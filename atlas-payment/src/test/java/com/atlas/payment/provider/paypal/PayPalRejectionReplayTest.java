package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.dto.request.PaymentRequest;
import com.atlas.payment.dto.response.PaymentProviderResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentOperation;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 실제 Sandbox가 준 거절 본문(EXP-094, 식별자·개인 정보를 치환한 기록)을 운영 {@code PayPalRestClient}와
 * {@code PayPalPaymentProvider}에 그대로 재생해 확정 분류 경계를 고정한다.
 *
 * <p>확정으로 보는 것은 ADR-0079의 세 가지뿐이고, "4xx인데 작업은 일어난" 기록은 확정이 되면 안 된다.
 * 어느 한 쪽으로 분류가 움직이면 이 테스트가 실패한다.
 */
class PayPalRejectionReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode RECORDED = recorded();
    private static final String ORDER_ID = "5O190127TN364715T";
    private static final String AUTHORIZATION_ID = "3C679366HH908993F";
    private static final Instant BEFORE_MOCKED_EXPIRY = Instant.parse("2026-11-25T00:00:00Z");
    private static final Instant AFTER_MOCKED_EXPIRY = Instant.parse("2027-02-01T00:00:00Z");

    private final Map<String, Reply> replies = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private HttpServer server;
    private PayPalRestClient client;

    @BeforeEach
    void startRecordedPayPal() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(key);
            Reply reply = replies.getOrDefault(key, new Reply(599, "{\"unexpected\":\"" + key + "\"}"));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCaptureBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
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

    static Stream<String> recordedOrderCreateRejections() {
        return RECORDED.propertyNames().stream().filter(name -> name.startsWith("orderCreateError")).sorted();
    }

    /** 주문 생성 400·422(+이슈): 기록마다 본문에 주문 ID가 없고, 운영은 호출 1회로 DECLINED에 도달한다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedOrderCreateRejections")
    void recordedOrderCreateRejectionIsDefinitiveAfterOneCall(String fixture) {
        JsonNode recorded = RECORDED.get(fixture);
        assertThat(recorded.get("status").asInt()).isIn(400, 422);
        assertThat(recorded.get("body").has("id")).as("거절 본문에 주문 ID가 없다").isFalse();
        assertThat(recorded.get("body").get("details").get(0).get("issue").asString()).isNotBlank();
        reply("POST /v2/checkout/orders", fixture);

        PaymentProviderResponse response = provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.AUTHORIZATION, null, null));

        assertThat(response.outcome()).isEqualTo(PaymentProviderResponse.Outcome.DECLINED);
        assertThat(calls).containsExactly("POST /v2/checkout/orders");
    }

    /**
     * 주문 생성의 4xx 중 400·422가 아니거나 PayPal 이슈가 없는 응답은 확정이 아니다. 404는 실제 Sandbox 기록이고
     * 429·이슈 없는 400은 PayPal이 보낸 본문이 아니라 이 테스트가 만든 모양이다(실제 429는 관측하지 못했다).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("orderCreateResponsesThatAreNotDefinitive")
    void orderCreateClientErrorsOutsideTheRuleStayUnknown(String name, Reply reply) {
        replies.put("POST /v2/checkout/orders", reply);

        assertThatThrownBy(() -> provider(BEFORE_MOCKED_EXPIRY).perform(request(PaymentOperation.AUTHORIZATION, null, null)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
        assertThat(calls).containsExactly("POST /v2/checkout/orders");
    }

    static Stream<Arguments> orderCreateResponsesThatAreNotDefinitive() {
        JsonNode notFound = RECORDED.get("error404UnknownResource");
        return Stream.of(
                Arguments.of("recorded 404 RESOURCE_NOT_FOUND", new Reply(notFound.get("status").asInt(),
                        notFound.get("body").toString())),
                Arguments.of("synthetic 429 with issue", new Reply(429,
                        "{\"name\":\"RATE_LIMIT_REACHED\",\"details\":[{\"issue\":\"RATE_LIMIT_REACHED\"}]}")),
                Arguments.of("synthetic 400 without issue", new Reply(400, "{\"name\":\"INVALID_REQUEST\"}")),
                Arguments.of("synthetic 422 HTML body", new Reply(422, "<html>Unprocessable</html>")));
    }

    /** void된 authorization의 capture 422 AUTHORIZATION_VOIDED + 조회가 VOIDED이고 capture가 없으면 VOIDED. */
    @Test
    void recordedVoidedCaptureEndsVoidedOnlyAfterTheOrderLookupAgrees() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture", "error422AuthorizationVoided");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderAuthorizationVoided");

        PaymentProviderResponse response = provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID));

        assertThat(response.outcome()).isEqualTo(PaymentProviderResponse.Outcome.VOIDED);
        assertThat(calls).containsExactly("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture",
                "GET /v2/checkout/orders/" + ORDER_ID);
    }

    /**
     * 일부 capture 뒤 남은 부분을 void하면 새 Request-Id의 capture가 422 AUTHORIZATION_VOIDED인데 주문에는
     * capture가 있다(EXP-094 H4, Sandbox). 이 거절을 단독으로 VOIDED로 보면 잡힌 돈이 있는 시도를 끝낸다.
     */
    @Test
    void recordedVoidedCaptureThatFollowsAPartialCaptureIsNeverVoided() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture",
                "error422AuthorizationVoidedAfterPartialCapture");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderPartiallyCapturedThenVoided");

        PaymentProviderResponse response = provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID));

        assertThat(response.outcome()).isNotEqualTo(PaymentProviderResponse.Outcome.VOIDED);
        assertThat(response.outcome()).isEqualTo(PaymentProviderResponse.Outcome.UNKNOWN);
    }

    /** 이미 capture된 authorization의 두 번째 capture는 작업이 일어난 뒤의 4xx라 확정이 아니다(UNKNOWN, 조회 없음). */
    @Test
    void recordedAlreadyCapturedIsNotDefinitive() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture",
                "error422AuthorizationAlreadyCaptured");

        assertThatThrownBy(() -> provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
        assertThat(calls).containsExactly("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture");
    }

    /** void 재호출 422 PREVIOUSLY_VOIDED는 목표 상태에 이미 있다. capture된 것을 void한 PREVIOUSLY_CAPTURED는 아니다. */
    @Test
    void recordedPreviouslyVoidedIsDoneButPreviouslyCapturedIsNot() {
        String voidPath = "POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/void";
        reply(voidPath, "error422PreviouslyVoided");
        assertThat(provider(BEFORE_MOCKED_EXPIRY).perform(request(PaymentOperation.VOID, ORDER_ID, AUTHORIZATION_ID))
                .outcome()).isEqualTo(PaymentProviderResponse.Outcome.VOIDED);

        reply(voidPath, "error422PreviouslyCaptured");
        assertThatThrownBy(() -> provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.VOID, ORDER_ID, AUTHORIZATION_ID)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    /** authorize 거절은 어느 것도 확정으로 분류하지 않는다(이미 authorize된 주문). */
    @Test
    void recordedAuthorizeRejectionIsNotDefinitive() {
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderApproved");
        reply("POST /v2/checkout/orders/" + ORDER_ID + "/authorize", "error422OrderAlreadyAuthorized");

        assertThatThrownBy(() -> provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.AUTHORIZATION, ORDER_ID, null)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    /**
     * Sandbox 모의 헤더는 422 AUTHORIZATION_EXPIRED만 만들고 authorization은 CREATED 그대로 만료 29일 전이며
     * 이후 일반 capture가 성공했다(EXP-094 H6). 조회 만료 시각이 아직 오지 않았으면 이 거절로 종결하지 않는다.
     */
    @Test
    void expiredRejectionWithALiveAuthorizationIsNotEndedBeforeTheExpiryTime() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture", "mockError422AuthorizationExpired");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderAuthorizedAfterMockedExpiry");

        assertThatThrownBy(() -> provider(BEFORE_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    /** 같은 기록에서 시계만 만료 시각 뒤로 옮기면 만료 거절은 VOIDED로 끝난다(만료가 실제로 지난 경우의 모양). */
    @Test
    void expiredRejectionAfterTheExpiryTimeEndsVoided() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture", "mockError422AuthorizationExpired");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderAuthorizedAfterMockedExpiry");

        PaymentProviderResponse response = provider(AFTER_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID));

        assertThat(response.outcome()).isEqualTo(PaymentProviderResponse.Outcome.VOIDED);
    }

    /** 거절 본문이 AUTHORIZATION_VOIDED여도 조회가 아직 CREATED이면(어긋남) 종결하지 않고 복구가 다시 본다. */
    @Test
    void voidedRejectionWhileTheLookupStillShowsALiveAuthorizationStaysUnknown() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture", "error422AuthorizationVoided");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderAuthorizedAfterMockedExpiry");

        assertThatThrownBy(() -> provider(AFTER_MOCKED_EXPIRY)
                .perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID)))
                .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.code())
                        .isEqualTo(PaymentException.Code.PAYMENT_PROVIDER_UNAVAILABLE));
    }

    /** 만료 거절이어도 조회가 VOIDED이면 시계와 무관하게 종결한다. */
    @Test
    void expiredRejectionWithAVoidedLookupEndsVoided() {
        reply("POST /v2/payments/authorizations/" + AUTHORIZATION_ID + "/capture", "mockError422AuthorizationExpired");
        reply("GET /v2/checkout/orders/" + ORDER_ID, "getOrderAuthorizationVoided");

        assertThat(provider(BEFORE_MOCKED_EXPIRY).perform(request(PaymentOperation.CAPTURE, ORDER_ID, AUTHORIZATION_ID))
                .outcome()).isEqualTo(PaymentProviderResponse.Outcome.VOIDED);
    }

    /** 실제 Sandbox의 PAYMENT.AUTHORIZATION.VOIDED 전달 본문에서 운영 파서가 읽는 값(EXP-094). */
    @Test
    void recordedAuthorizationVoidedWebhookIsParsedToTheAuthorizationAndItsOrder() {
        String raw = RECORDED.get("webhookAuthorizationVoided").get("body").toString();

        var event = client.parseWebhook(raw);

        assertThat(event.eventType()).isEqualTo("PAYMENT.AUTHORIZATION.VOIDED");
        assertThat(event.resourceId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(event.resourceStatus()).isEqualTo("VOIDED");
        assertThat(event.providerOrderId()).isEqualTo(ORDER_ID);
    }

    private PayPalPaymentProvider provider(Instant now) {
        return new PayPalPaymentProvider(client, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void reply(String key, String fixture) {
        JsonNode recorded = RECORDED.get(fixture);
        assertThat(recorded).as("기록된 응답 " + fixture).isNotNull();
        replies.put(key, new Reply(recorded.get("status").asInt(), recorded.get("body").toString()));
    }

    private static PaymentRequest request(PaymentOperation operation, String orderId, String authorizationId) {
        return new PaymentRequest(operation, UUID.randomUUID(), UUID.randomUUID(), 100L, "USD", orderId,
                authorizationId, "create-1", "authorize-1", "capture-1", "void-1",
                "https://example.com/return", "https://example.com/cancel");
    }

    private static JsonNode recorded() {
        try (InputStream input = PayPalRejectionReplayTest.class
                .getResourceAsStream("/paypal-sandbox/responses.json")) {
            return JSON.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("recorded PayPal Sandbox responses are missing", exception);
        }
    }

    private record Reply(int status, String body) { }
}
