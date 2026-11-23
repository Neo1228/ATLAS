package com.atlas.payment.provider.paypal.payouts;

import com.atlas.payment.dto.request.PayPalPayoutRequest;
import com.atlas.payment.dto.response.PayPalPayoutBatchResponse;
import com.atlas.payment.provider.paypal.PayPalProperties;
import com.atlas.payment.provider.paypal.PayPalPayoutsProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "atlas.payment.paypal.payouts", name = "enabled", havingValue = "true")
public class PayPalPayoutsRestClient implements PayPalPayoutsProvider {
    private static final String REGISTRATION_ID = "paypal-payouts";
    private static final String CREATE_PATH = "/v1/payments/payouts";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final RestClient.Builder builder;
    private final PayPalProperties properties;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final ObjectMapper objectMapper;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final JdkClientHttpRequestFactory requestFactory;

    public PayPalPayoutsRestClient(RestClient.Builder builder, PayPalProperties properties,
            PayPalPayoutsProperties payoutsProperties, OAuth2AuthorizedClientManager authorizedClientManager,
            ObjectMapper objectMapper,
            @Value("${atlas.payment.paypal.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${atlas.payment.paypal.read-timeout:PT10S}") Duration readTimeout) {
        this.builder = Objects.requireNonNull(builder).clone();
        this.properties = Objects.requireNonNull(properties);
        if (!Objects.requireNonNull(payoutsProperties).isEnabled()) throw new IllegalStateException("PayPal Payouts is disabled");
        this.authorizedClientManager = Objects.requireNonNull(authorizedClientManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.connectTimeout = Objects.requireNonNull(connectTimeout);
        this.readTimeout = Objects.requireNonNull(readTimeout);
        this.properties.validateBaseUrl();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(this.connectTimeout).build();
        this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
        this.requestFactory.setReadTimeout(this.readTimeout);
    }

    @Override
    public PayPalPayoutBatchResponse createBatch(PayPalPayoutRequest request) {
        Objects.requireNonNull(request, "request");
        validateId(request.senderBatchId());
        validateId(request.senderItemId());
        validateId(request.requestId());
        LinkedHashMap<String, Object> payoutItem = new LinkedHashMap<>();
        payoutItem.put("recipient_type", "EMAIL");
        payoutItem.put("receiver", request.recipientEmail());
        payoutItem.put("sender_item_id", request.senderItemId());
        payoutItem.put("amount", Map.of("value", BigDecimal.valueOf(request.amountMinor(), 2).toPlainString(), "currency", "USD"));
        Map<String, Object> body = Map.of(
                "sender_batch_header", Map.of("sender_batch_id", request.senderBatchId(), "email_subject", "You have a payout"),
                "items", List.of(payoutItem));
        return execute(HttpMethod.POST, CREATE_PATH, request.senderBatchId(), request.requestId(), body);
    }

    @Override
    public PayPalPayoutBatchResponse getBatch(String payoutBatchId) {
        validateId(payoutBatchId);
        String path = "/v1/payments/payouts/" + UriUtils.encodePathSegment(payoutBatchId, StandardCharsets.UTF_8);
        // 불명확한 재시도도 durable batch ID를 재사용하며 새 ID를 발급하지 않는다.
        return execute(HttpMethod.GET, path, null, null, null);
    }

    private PayPalPayoutBatchResponse execute(HttpMethod method, String path, String senderBatchId,
            String requestId, Map<String, Object> body) {
        try {
            var request = api().method(method).uri(path);
            if (requestId != null) request.header("PayPal-Request-Id", requestId);
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((ignoredRequest, response) -> {
                byte[] bytes;
                try {
                    bytes = response.getBody().readAllBytes();
                } catch (IOException exception) {
                    throw payoutException("Could not read PayPal Payouts response", exception,
                            PayPalPayoutException.Classification.UNKNOWN, senderBatchId, null);
                }
                int status = response.getStatusCode().value();
                if (status < 200 || status >= 300) {
                    String payload = new String(bytes, StandardCharsets.UTF_8);
                    if (isDuplicateBatch(payload)) {
                        throw payoutException("PayPal reports a duplicate sender batch", null,
                                PayPalPayoutException.Classification.DUPLICATE_BATCH, senderBatchId,
                                extractOriginalBatchId(payload));
                    }
                    PayPalPayoutException.Classification classification = status == 429
                            ? PayPalPayoutException.Classification.RETRYABLE
                            : status >= 500 ? PayPalPayoutException.Classification.UNKNOWN
                                    : method == HttpMethod.POST && status >= 400 && status < 500
                                            ? PayPalPayoutException.Classification.DEFINITIVE_NO_SEND
                                            : PayPalPayoutException.Classification.UNKNOWN;
                    throw payoutException("PayPal Payouts HTTP " + status, null, classification, senderBatchId, null);
                }
                return parse(bytes, senderBatchId);
            });
        } catch (PayPalPayoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw payoutException("PayPal Payouts transport outcome is unknown", exception,
                    PayPalPayoutException.Classification.UNKNOWN, senderBatchId, null);
        }
    }

    private PayPalPayoutBatchResponse parse(byte[] bytes, String fallbackBatchId) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode header = root.path("batch_header");
            String batchId = text(header, "payout_batch_id");
            String senderBatchId = text(header, "sender_batch_id");
            JsonNode batchAmount = header.path("amount");
            JsonNode batchFees = header.path("fees");
            List<PayPalPayoutBatchResponse.Item> items = new ArrayList<>();
            JsonNode entries = root.path("items");
            if (entries.isArray()) for (JsonNode item : entries) items.add(parseItem(item));
            // Batch 상태만으로 payout item 완료를 추정하지 않는다.
            return new PayPalPayoutBatchResponse(batchId,
                    senderBatchId == null ? fallbackBatchId : senderBatchId,
                    text(header, "batch_status"), decimal(batchAmount, "value"), text(batchAmount, "currency"),
                    decimal(batchFees, "value"), text(batchFees, "currency"), items);
        } catch (JacksonException exception) {
            throw payoutException("Invalid PayPal Payouts response", exception,
                    PayPalPayoutException.Classification.UNKNOWN, fallbackBatchId, null);
        }
    }

    private PayPalPayoutBatchResponse.Item parseItem(JsonNode item) {
        JsonNode payoutItem = item.path("payout_item");
        JsonNode amount = payoutItem.path("amount");
        JsonNode fee = item.path("payout_item_fee");
        JsonNode conversion = item.path("currency_conversion");
        return new PayPalPayoutBatchResponse.Item(text(item, "payout_item_id"), text(payoutItem, "sender_item_id"),
                text(item, "transaction_status"), decimal(amount, "value"), text(amount, "currency"),
                decimal(fee, "value"), text(fee, "currency"),
                decimal(conversion.path("from_amount"), "value"),
                text(conversion.path("from_amount"), "currency"),
                decimal(conversion.path("to_amount"), "value"),
                text(conversion.path("to_amount"), "currency"),
                decimal(conversion, "exchange_rate"), item.has("currency_conversion"));
    }

    private boolean isDuplicateBatch(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String material = (Objects.toString(text(root, "name"), "") + " "
                    + Objects.toString(text(root, "message"), "") + " "
                    + Objects.toString(text(root, "details", "0", "issue"), "")).toLowerCase(Locale.ROOT);
            return material.contains("sender_batch_id") && (material.contains("duplicate") || material.contains("already"));
        } catch (JacksonException ignored) { return false; }
    }

    private String extractOriginalBatchId(String payload) {
        try {
            JsonNode links = objectMapper.readTree(payload).path("links");
            if (!links.isArray()) return null;
            for (JsonNode link : links) {
                String href = text(link, "href");
                if (href == null) continue;
                try {
                    String path = URI.create(href).getPath();
                    if (path == null || !path.matches(".*/v1/payments/payouts/[A-Za-z0-9_-]{1,128}/?")) continue;
                    String id = path.replaceAll(".*/v1/payments/payouts/", "").replaceAll("/$", "");
                    if (SAFE_ID.matcher(id).matches()) return id;
                } catch (IllegalArgumentException ignored) { /* Never follow provider-supplied links. */ }
            }
        } catch (JacksonException ignored) { /* Duplicate can be identified without an original batch link. */ }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String key) {
        String value = text(node, key);
        if (value == null) return null;
        try { return new BigDecimal(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String text(JsonNode node, String... path) {
        for (String segment : path) node = node.path(segment);
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private void validateId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) throw new IllegalArgumentException("Unsafe PayPal payout identifier");
    }

    private PayPalPayoutException payoutException(String message, Throwable cause,
            PayPalPayoutException.Classification classification, String senderBatchId, String originalBatchId) {
        return new PayPalPayoutException(message, cause, classification, senderBatchId, originalBatchId);
    }

    private RestClient api() {
        OAuth2AuthorizedClient authorized = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID).principal("paypal-payouts").build());
        if (authorized == null || authorized.getAccessToken() == null) {
            throw payoutException("PayPal Payouts OAuth authorization failed", null,
                    PayPalPayoutException.Classification.RETRYABLE, null, null);
        }
        return builder.clone().baseUrl(properties.getBaseUrl().replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + authorized.getAccessToken().getTokenValue())
                .build();
    }
}
