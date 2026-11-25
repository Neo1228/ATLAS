package com.atlas.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 통합 테스트용 PayPal 대역이 실제 Sandbox 에서 관측한 규칙(EXP-081)을 따르는지 고정한다.
 * Spring context 와 DB 없이 대역만 호출한다. 대역이 이 규칙에서 벗어나면 결과 불명 복구 테스트가
 * 실제 PayPal 에는 없는 성공을 근거로 통과할 수 있다.
 */
class PayPalDoubleContractTest extends PayPalIntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String ORDER_BODY = "{\"intent\":\"AUTHORIZE\",\"purchase_units\":[{\"amount\":"
            + "{\"currency_code\":\"USD\",\"value\":\"1.00\"}}]}";
    private static final String CAPTURE_BODY = "{\"amount\":{\"currency_code\":\"USD\",\"value\":\"1.00\"},"
            + "\"final_capture\":true}";

    @Test
    void unknownIdsAreAnsweredWithTheRealNotFoundBody() throws Exception {
        List<String[]> calls = List.of(
                new String[] {"GET", "/v2/checkout/orders/UNKNOWN-ORDER"},
                new String[] {"POST", "/v2/checkout/orders/UNKNOWN-ORDER/authorize"},
                new String[] {"POST", "/v2/payments/authorizations/UNKNOWN-AUTH/capture"},
                new String[] {"POST", "/v2/payments/authorizations/UNKNOWN-AUTH/void"},
                new String[] {"POST", "/v2/payments/captures/UNKNOWN-CAPTURE/refund"},
                new String[] {"GET", "/v2/payments/refunds/UNKNOWN-REFUND"});

        for (String[] call : calls) {
            Reply reply = send(call[0], call[1], "{}", "unknown-" + call[1].hashCode());

            assertThat(reply.status()).as(call[1]).isEqualTo(404);
            assertThat(reply.body().get("name").asString()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(reply.body().get("details").get(0).get("issue").asString()).isEqualTo("INVALID_RESOURCE_ID");
            assertThat(reply.body().has("debug_id")).isTrue();
        }
    }

    @Test
    void repeatingARequestIdReplaysTheFirstResultEvenWithAnotherBody() throws Exception {
        String orderId = createOrder("create-1");
        assertThat(createOrderWithBody("create-1", ORDER_BODY.replace("1.00", "9.00"))).isEqualTo(orderId);

        String authorizationId = authorize(orderId, "authorize-1");
        assertThat(authorize(orderId, "authorize-1")).isEqualTo(authorizationId);

        String captureId = capture(authorizationId, "capture-1").body().get("id").asString();
        assertThat(capture(authorizationId, "capture-1").body().get("id").asString()).isEqualTo(captureId);

        String refund = "{\"amount\":{\"value\":\"0.40\",\"currency_code\":\"USD\"}}";
        String refundId = send("POST", "/v2/payments/captures/" + captureId + "/refund", refund, "refund-1")
                .body().get("id").asString();
        assertThat(send("POST", "/v2/payments/captures/" + captureId + "/refund", refund, "refund-1")
                .body().get("id").asString()).isEqualTo(refundId);
    }

    @Test
    void secondWritesUnderANewRequestIdAreRejectedLikeSandbox() throws Exception {
        String orderId = createOrder("create-2");
        String authorizationId = authorize(orderId, "authorize-2");
        assertThat(issueOf(send("POST", "/v2/checkout/orders/" + orderId + "/authorize", "{}", "authorize-3")))
                .isEqualTo("ORDER_ALREADY_AUTHORIZED");

        String captureId = capture(authorizationId, "capture-2").body().get("id").asString();
        assertThat(issueOf(capture(authorizationId, "capture-3"))).isEqualTo("AUTHORIZATION_ALREADY_CAPTURED");

        String refundPath = "/v2/payments/captures/" + captureId + "/refund";
        assertThat(issueOf(send("POST", refundPath,
                "{\"amount\":{\"value\":\"2.00\",\"currency_code\":\"USD\"}}", "refund-over")))
                .isEqualTo("REFUND_AMOUNT_EXCEEDED");
        assertThat(send("POST", refundPath, "{}", "refund-full").status()).isEqualTo(200);
        assertThat(issueOf(send("POST", refundPath, "{}", "refund-again"))).isEqualTo("CAPTURE_FULLY_REFUNDED");
    }

    private static String createOrder(String requestId) throws Exception {
        return createOrderWithBody(requestId, ORDER_BODY);
    }

    private static String createOrderWithBody(String requestId, String body) throws Exception {
        return send("POST", "/v2/checkout/orders", body, requestId).body().get("id").asString();
    }

    private static String authorize(String orderId, String requestId) throws Exception {
        Reply reply = send("POST", "/v2/checkout/orders/" + orderId + "/authorize", "{}", requestId);
        return reply.body().get("purchase_units").get(0).get("payments").get("authorizations").get(0)
                .get("id").asString();
    }

    private static Reply capture(String authorizationId, String requestId) throws Exception {
        return send("POST", "/v2/payments/authorizations/" + authorizationId + "/capture", CAPTURE_BODY,
                requestId);
    }

    private static String issueOf(Reply reply) {
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.body().get("name").asString()).isEqualTo("UNPROCESSABLE_ENTITY");
        return reply.body().get("details").get(0).get("issue").asString();
    }

    private static Reply send(String method, String path, String body, String requestId)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(PAYPAL.url(path).toString()))
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .header("PayPal-Request-Id", requestId);
        BodyPublisher payload = "GET".equals(method) ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        HttpResponse<String> response = HTTP.send(builder.method(method, payload).build(),
                BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()));
    }

    private record Reply(int status, JsonNode body) { }
}
