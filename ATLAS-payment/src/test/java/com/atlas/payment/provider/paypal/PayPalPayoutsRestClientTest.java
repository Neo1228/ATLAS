package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.payment.provider.paypal.payouts.PayPalPayoutBatch;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutException;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutRequest;
import com.atlas.payment.provider.paypal.payouts.PayPalPayoutsRestClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PayPalPayoutsRestClientTest {
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(201);
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedRequestId = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private HttpServer server;
    private PayPalPayoutsRestClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            receivedRequestId.set(exchange.getRequestHeaders().getFirst("PayPal-Request-Id"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setLocalProxyEnabled(true);
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
        when(authorized.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(300)));
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any(OAuth2AuthorizeRequest.class))).thenReturn(authorized);
        objectMapper = new ObjectMapper();
        PayPalPayoutsProperties payoutsProperties = new PayPalPayoutsProperties();
        payoutsProperties.setEnabled(true);
        client = new PayPalPayoutsRestClient(RestClient.builder(), properties, payoutsProperties, manager,
                objectMapper, Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }


    @Test
    void createUsesStableIdsUsdCentsAndExpectedPathHeaderAndBody() throws Exception {
        responseBody.set("{\"batch_header\":{\"payout_batch_id\":\"PAYOUT-1\",\"sender_batch_id\":\"batch-stable\",\"batch_status\":\"PENDING\",\"amount\":{\"value\":\"12.34\",\"currency\":\"USD\"}},\"items\":[{\"payout_item\":{\"sender_item_id\":\"item-stable\",\"amount\":{\"value\":\"12.34\",\"currency\":\"USD\"}},\"transaction_status\":\"UNCLAIMED\"}]}");
        PayPalPayoutBatch result = client.createBatch(new PayPalPayoutRequest(
                "batch-stable", "item-stable", "request-stable", "payee@example.com", 1234, "USD"));
        JsonNode sent = objectMapper.readTree(receivedBody.get());
        assertThat(receivedPath.get()).isEqualTo("/v1/payments/payouts");
        assertThat(receivedRequestId.get()).isEqualTo("request-stable");
        assertThat(sent.path("sender_batch_header").path("sender_batch_id").asString()).isEqualTo("batch-stable");
        assertThat(sent.path("items").get(0).path("recipient_type").asString()).isEqualTo("EMAIL");
        assertThat(sent.path("items").get(0).path("sender_item_id").asString()).isEqualTo("item-stable");
        assertThat(sent.path("items").get(0).path("amount").path("value").asString()).isEqualTo("12.34");
        assertThat(result.batchStatus()).isEqualTo("PENDING");
        assertThat(result.items().getFirst().isUnclaimed()).isTrue();
        assertThat(result.items().getFirst().isFinalSuccess()).isFalse();
        assertThat(result.items().getFirst().currencyConversionPresent()).isFalse();
        assertThat(receivedBody.get()).doesNotContain("recipientEmail");
    }

    @Test
    void getBatchNormalizesFinalSuccessAndDoesNotFollowProviderLinks() {
        responseBody.set("{\"batch_header\":{\"payout_batch_id\":\"PAYOUT-2\",\"sender_batch_id\":\"PAYOUT-2\",\"batch_status\":\"SUCCESS\"},\"items\":[{\"payout_item_id\":\"ITEM-2\",\"payout_item\":{\"sender_item_id\":\"item-2\",\"amount\":{\"value\":\"4.50\",\"currency\":\"USD\"}},\"transaction_status\":\"SUCCESS\",\"payout_item_fee\":{\"value\":\"0.25\",\"currency\":\"USD\"},\"currency_conversion\":{\"from_amount\":{\"value\":\"4.50\",\"currency\":\"USD\"},\"to_amount\":{\"value\":\"6.12\",\"currency\":\"CAD\"},\"exchange_rate\":\"1.36\"}}]}");
        PayPalPayoutBatch result = client.getBatch("PAYOUT-2");
        assertThat(receivedPath.get()).isEqualTo("/v1/payments/payouts/PAYOUT-2");
        assertThat(receivedRequestId.get()).isNull();
        assertThat(result.items().getFirst().isFinalSuccess()).isTrue();
        assertThat(result.items().getFirst().amount()).isEqualByComparingTo("4.50");
        assertThat(result.items().getFirst().fee()).isEqualByComparingTo("0.25");
        assertThat(result.items().getFirst().conversionFromAmount()).isEqualByComparingTo("4.50");
        assertThat(result.items().getFirst().conversionFromCurrency()).isEqualTo("USD");
        assertThat(result.items().getFirst().conversionToAmount()).isEqualByComparingTo("6.12");
        assertThat(result.items().getFirst().conversionToCurrency()).isEqualTo("CAD");
        assertThat(result.items().getFirst().conversionRate()).isEqualByComparingTo("1.36");
        assertThat(result.items().getFirst().currencyConversionPresent()).isTrue();
    }

    @Test
    void preservesAnEmptyCurrencyConversionObjectAsPresent() {
        responseBody.set("{\"batch_header\":{\"payout_batch_id\":\"PAYOUT-3\",\"sender_batch_id\":\"PAYOUT-3\",\"batch_status\":\"SUCCESS\"},\"items\":[{\"payout_item_id\":\"ITEM-3\",\"payout_item\":{\"sender_item_id\":\"item-3\",\"amount\":{\"value\":\"1.00\",\"currency\":\"USD\"}},\"transaction_status\":\"SUCCESS\",\"currency_conversion\":{}}]}");

        PayPalPayoutBatch result = client.getBatch("PAYOUT-3");

        assertThat(result.items().getFirst().currencyConversionPresent()).isTrue();
        assertThat(result.items().getFirst().conversionFromAmount()).isNull();
    }

    @Test
    void duplicateBatchExtractsOnlyBatchIdFromHateoasPath() {
        responseStatus.set(422);
        responseBody.set("{\"name\":\"VALIDATION_ERROR\",\"message\":\"Duplicate sender_batch_id\",\"details\":[{\"issue\":\"DUPLICATE_SENDER_BATCH_ID\"}],\"links\":[{\"rel\":\"self\",\"href\":\"https://evil.invalid/v1/payments/payouts/ORIGINAL-1\"}]}");
        assertThatThrownBy(() -> client.createBatch(new PayPalPayoutRequest(
                "batch-stable", "item-stable", "request-stable", "payee@example.com", 100, "USD")))
                .isInstanceOfSatisfying(PayPalPayoutException.class, exception -> {
                    assertThat(exception.getClassification()).isEqualTo(PayPalPayoutException.Classification.DUPLICATE_BATCH);
                    assertThat(exception.getOriginalBatchId()).isEqualTo("ORIGINAL-1");
                    assertThat(exception.getSenderBatchId()).isEqualTo("batch-stable");
                });
        assertThat(receivedPath.get()).isEqualTo("/v1/payments/payouts");
    }

    @Test
    void rateLimitIsRetryableWithoutChangingTheCallerIds() {
        responseStatus.set(429);
        responseBody.set("{}");
        assertThatThrownBy(() -> client.createBatch(new PayPalPayoutRequest(
                "batch-stable", "item-stable", "request-stable", "payee@example.com", 100, "USD")))
                .isInstanceOfSatisfying(PayPalPayoutException.class, exception -> {
                    assertThat(exception.getClassification()).isEqualTo(PayPalPayoutException.Classification.RETRYABLE);
                    assertThat(exception.getSenderBatchId()).isEqualTo("batch-stable");
                });
        assertThat(receivedRequestId.get()).isEqualTo("request-stable");
        assertThat(receivedBody.get()).contains("batch-stable", "item-stable");
    }

    @Test
    void serverErrorHasUnknownOutcomeAndRequiresReconciliation() {
        responseStatus.set(500);
        responseBody.set("{}");
        assertThatThrownBy(() -> client.createBatch(new PayPalPayoutRequest(
                "batch-stable", "item-stable", "request-stable", "payee@example.com", 100, "USD")))
                .isInstanceOfSatisfying(PayPalPayoutException.class, exception ->
                        assertThat(exception.getClassification())
                                .isEqualTo(PayPalPayoutException.Classification.UNKNOWN));
    }

    @Test
    void missingProviderBatchIdIsNotConfusedWithSenderBatchId() {
        responseBody.set("{\"batch_header\":{\"sender_batch_id\":\"batch-stable\",\"batch_status\":\"PENDING\"}}");
        PayPalPayoutBatch result = client.createBatch(new PayPalPayoutRequest(
                "batch-stable", "item-stable", "request-stable", "payee@example.com", 100, "USD"));
        assertThat(result.batchId()).isNull();
        assertThat(result.senderBatchId()).isEqualTo("batch-stable");
    }

}
