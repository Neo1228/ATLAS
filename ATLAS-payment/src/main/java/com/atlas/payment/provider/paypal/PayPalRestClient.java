package com.atlas.payment.provider.paypal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

    /** PayPal Orders v2/Payments v2 API 요청과 응답 매핑. 요청은 자동 재시도하지 않음. */
@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "paypal")
public class PayPalRestClient {
    private static final String REQUEST_ID = "PayPal-Request-Id";
    private static final String REGISTRATION_ID = "paypal";

    private final RestClient.Builder builder;
    private final PayPalProperties properties;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final ObjectMapper objectMapper;

    public PayPalRestClient(RestClient.Builder builder, PayPalProperties properties,
            OAuth2AuthorizedClientManager authorizedClientManager, ObjectMapper objectMapper) {
        this.builder = Objects.requireNonNull(builder).clone();
        this.properties = Objects.requireNonNull(properties);
        this.authorizedClientManager = Objects.requireNonNull(authorizedClientManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public OrderResult createOrder(String referenceId, BigDecimal amount, String currency,
            String returnUrl, String cancelUrl, String requestId) {
        Map<String, Object> unit = Map.of("reference_id", referenceId,
                "amount", Map.of("currency_code", currency, "value", amount.toPlainString()));
        Map<String, Object> body = Map.of("intent", "AUTHORIZE", "purchase_units", List.of(unit),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "return_url", returnUrl, "cancel_url", cancelUrl, "user_action", "PAY_NOW"))));
        Map<String, Object> response = post("/v2/checkout/orders", body, requestId);
        return new OrderResult(text(response, "id"), text(response, "status"), approvalUrl(response));
    }

    public OrderStatus getOrder(String orderId) {
        Map<String, Object> response = api().get().uri("/v2/checkout/orders/{id}", orderId)
                .retrieve().body(mapType());
        Map<String, Object> authorization = firstResource(response, "authorization");
        Map<String, Object> capture = firstResource(response, "capture");
        return new OrderStatus(text(response, "id"), text(response, "status"),
                orderAmount(response), orderCurrency(response),
                authorization == null ? null : text(authorization, "id"),
                authorization == null ? null : text(authorization, "status"),
                capture == null ? null : text(capture, "id"),
                capture == null ? null : text(capture, "status"),
                resourceAmount(response, "capture"), resourceCurrency(response, "capture"));
    }

    public AuthorizationResult authorizeOrder(String orderId, String requestId) {
        Map<String, Object> response = post("/v2/checkout/orders/{id}/authorize".replace("{id}", orderId),
                Map.of(), requestId);
        return new AuthorizationResult(resourceId(response, "authorization"),
                resourceStatus(response, "authorization"));
    }

    public CaptureResult captureAuthorization(String authorizationId, BigDecimal amount,
            String currency, String requestId) {
        Map<String, Object> body = Map.of("amount", Map.of("currency_code", currency,
                "value", amount.toPlainString()), "final_capture", true);
        Map<String, Object> response = post("/v2/payments/authorizations/{id}/capture".replace("{id}", authorizationId),
                body, requestId);
        return new CaptureResult(resourceId(response, "capture"), resourceStatus(response, "capture"),
                resourceAmount(response, "capture"), resourceCurrency(response, "capture"));
    }

    public void voidAuthorization(String authorizationId, String requestId) {
        api().post().uri("/v2/payments/authorizations/{id}/void", authorizationId)
                .header(REQUEST_ID, requestId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of()).retrieve().toBodilessEntity();
    }

    public RefundResult refundCapture(String captureId, BigDecimal amount, String currency, String requestId) {
        Map<String, Object> body = amount == null ? Map.of()
                : Map.of("amount", Map.of("value", amount.toPlainString(), "currency_code", currency));
        Map<String, Object> response = post("/v2/payments/captures/{id}/refund".replace("{id}", captureId),
                body, requestId);
        return new RefundResult(text(response, "id"), text(response, "status"),
                resourceAmount(response, "refund"), resourceCurrency(response, "refund"));
    }

    public RefundResult getRefund(String refundId) {
        try {
            Map<String, Object> response = api().get().uri("/v2/payments/refunds/{id}", refundId)
                    .retrieve().body(mapType());
            return new RefundResult(text(response, "id"), text(response, "status"),
                    resourceAmount(response, "refund"), resourceCurrency(response, "refund"));
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal refund lookup failed", exception);
        }
    }

    public WebhookVerificationResult verifyWebhook(PaymentWebhookSignature signature, String eventJson) {
        if (!properties.credentialsConfigured() || !properties.webhookConfigured()) {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                    "PayPal webhook credentials are not configured");
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("auth_algo", signature.authAlgorithm());
        body.put("cert_url", signature.certificateUrl());
        body.put("transmission_id", signature.transmissionId());
        body.put("transmission_sig", signature.transmissionSignature());
        body.put("transmission_time", signature.transmissionTime());
        body.put("webhook_id", required(properties.getWebhookId(), "PAYPAL_WEBHOOK_ID"));
        try {
            body.put("webhook_event", objectMapper.readValue(eventJson, new TypeReference<Map<String, Object>>() { }));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("PayPal webhook body is not valid JSON", exception);
        }
        Map<String, Object> response = postWebhook(body);
        return new WebhookVerificationResult("SUCCESS".equals(text(response, "verification_status")));
    }

    public PaymentWebhookEvent parseWebhook(String rawBody) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawBody,
                    new TypeReference<Map<String, Object>>() { });
            Object rawResource = event.get("resource");
            Map<?, ?> resource = rawResource instanceof Map<?, ?> map ? map : Map.of();
            Object rawAmount = resource.get("amount");
            Map<?, ?> amount = rawAmount instanceof Map<?, ?> map ? map : Map.of();
            Object rawSupplementary = resource.get("supplementary_data");
            Map<?, ?> supplementary = rawSupplementary instanceof Map<?, ?> map ? map : Map.of();
            Object rawRelatedIds = supplementary.get("related_ids");
            Map<?, ?> relatedIds = rawRelatedIds instanceof Map<?, ?> map ? map : Map.of();
            Object eventId = event.get("id");
            Object eventType = event.get("event_type");
            Object resourceId = resource.get("id");
            Object resourceStatus = resource.get("status");
            Object amountValue = amount.get("value");
            BigDecimal resourceAmount = amountValue instanceof String value
                    ? new BigDecimal(value) : null;
            Object resourceCurrency = amount.get("currency_code");
            Object providerOrderId = relatedIds.get("order_id");
            Object relatedCaptureId = resource.get("capture_id");
            if (relatedCaptureId == null) relatedCaptureId = relatedIds.get("capture_id");
            return new PaymentWebhookEvent(
                    eventId instanceof String value ? value : null,
                    eventType instanceof String value ? value : null,
                    resourceId instanceof String value ? value : null,
                    resourceStatus instanceof String value ? value : null,
                    providerOrderId instanceof String value ? value : null,
                    relatedCaptureId instanceof String value ? value : null,
                    resourceAmount,
                    resourceCurrency instanceof String value ? value : null);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("PayPal webhook body is not valid JSON", exception);
        }
    }

    private Map<String, Object> postWebhook(Object body) {
        try {
            return api().post().uri("/v1/notifications/verify-webhook-signature")
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(mapType());
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal webhook verification failed", exception);
        }
    }

    private Map<String, Object> post(String uri, Object body, String requestId) {
        validateRequestId(requestId);
        try {
            return api().post().uri(uri).header(REQUEST_ID, requestId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(mapType());
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal API request failed", exception);
        }
    }

    private RestClient api() {
        if (!properties.credentialsConfigured()) {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                    "PayPal client credentials are not configured");
        }
        try {
            OAuth2AuthorizedClient authorized = authorizedClientManager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                            .principal("paypal").build());
            if (authorized == null || authorized.getAccessToken() == null) {
                throw new PayPalUnavailableException("PayPal OAuth authorization failed");
            }
            return builder.clone().baseUrl(trimSlash(properties.getBaseUrl()))
                    .defaultHeader("Authorization", "Bearer " + authorized.getAccessToken().getTokenValue())
                    .build();
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal OAuth request failed", exception);
        }
    }

    private static String approvalUrl(Map<String, Object> response) {
        Object links = response == null ? null : response.get("links");
        if (links instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> link
                        && ("approve".equals(link.get("rel")) || "payer-action".equals(link.get("rel")))) {
                    Object href = link.get("href");
                    return href instanceof String text ? text : null;
                }
            }
        }
        return null;
    }

    private static BigDecimal orderAmount(Map<String, Object> response) {
        Map<String, Object> amount = orderAmountObject(response);
        String value = text(amount, "value");
        if (value == null) throw new PayPalUnavailableException("PayPal order amount is missing");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            throw new PayPalUnavailableException("PayPal order amount is invalid", invalid);
        }
    }

    private static String orderCurrency(Map<String, Object> response) {
        return text(orderAmountObject(response), "currency_code");
    }

    private static Map<String, Object> orderAmountObject(Map<String, Object> response) {
        Object units = response == null ? null : response.get("purchase_units");
        if (!(units instanceof List<?> values) || values.isEmpty()
                || !(values.get(0) instanceof Map<?, ?> unit)
                || !(unit.get("amount") instanceof Map<?, ?> amount)) {
            throw new PayPalUnavailableException("PayPal order amount is missing");
        }
        return castMap(amount);
    }

    private static String resourceId(Map<String, Object> response, String resource) {
        Map<String, Object> item = firstResource(response, resource);
        return item == null ? text(response, "id") : text(item, "id");
    }

    private static String resourceStatus(Map<String, Object> response, String resource) {
        Map<String, Object> item = firstResource(response, resource);
        return item == null ? text(response, "status") : text(item, "status");
    }

    private static BigDecimal resourceAmount(Map<String, Object> response, String resource) {
        Map<String, Object> amount = resourceAmountObject(response, resource);
        String value = amount == null ? null : text(amount, "value");
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String resourceCurrency(Map<String, Object> response, String resource) {
        Map<String, Object> amount = resourceAmountObject(response, resource);
        return amount == null ? null : text(amount, "currency_code");
    }

    private static Map<String, Object> resourceAmountObject(Map<String, Object> response, String resource) {
        Map<String, Object> item = firstResource(response, resource);
        Object value = item == null ? null : item.get("amount");
        if (!(value instanceof Map<?, ?>)) value = response == null ? null : response.get("amount");
        return value instanceof Map<?, ?> amount ? castMap(amount) : null;
    }
    private static Map<String, Object> firstResource(Map<String, Object> response, String resource) {
        Object units = response == null ? null : response.get("purchase_units");
        if (!(units instanceof List<?> values) || values.isEmpty()
                || !(values.get(0) instanceof Map<?, ?> unit)) return null;
        Object payments = unit.get("payments");
        if (!(payments instanceof Map<?, ?> paymentMap)) return null;
        Object resources = paymentMap.get(resource + "s");
        if (!(resources instanceof List<?> items) || items.isEmpty()
                || !(items.get(0) instanceof Map<?, ?> item)) return null;
        return castMap(item);
    }

    private static String text(Map<String, Object> response, String key) {
        Object value = response == null ? null : response.get(key);
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private static void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 38) {
            throw new IllegalArgumentException("PayPal request IDs must be 1-38 characters");
        }
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) throw new PayPalUnavailableException(key + " not configured");
        return value;
    }

    private static String trimSlash(String url) {
        return url == null ? "https://api-m.sandbox.paypal.com" : url.replaceAll("/+$", "");
    }

    public record OrderResult(String id, String status, String approvalUrl) { }
    public record OrderStatus(String id, String status, BigDecimal amount, String currency,
                              String authorizationId, String authorizationStatus,
                              String captureId, String captureStatus, BigDecimal captureAmount,
                              String captureCurrency) { }
    public record AuthorizationResult(String id, String status) { }
    public record CaptureResult(String id, String status, BigDecimal amount, String currency) { }
    public record RefundResult(String id, String status, BigDecimal amount, String currency) { }
    public record WebhookVerificationResult(boolean verified) { }

    public static class PayPalUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PayPalUnavailableException(String message) { super(message); }
        public PayPalUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
