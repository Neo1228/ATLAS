package com.atlas.payment.provider.paypal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

    /** PayPal Orders v2/Payments v2 API 요청과 응답 매핑. 요청은 자동 재시도하지 않음. */
@Component
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "paypal")
public class PayPalRestClient {
    private static final String REQUEST_ID = "PayPal-Request-Id";
    private static final String PREFER = "Prefer";
    private static final String REGISTRATION_ID = "paypal";
    private static final Pattern CAPTURE_LINK = Pattern.compile("/v2/payments/captures/([^/?#]+)$");

    private final RestClient.Builder builder;
    private final PayPalProperties properties;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final ObjectMapper objectMapper;
    private final JdkClientHttpRequestFactory requestFactory;
    /**
     * 외부 호출 상한. 기본값이 없으면 PayPal 이 응답하지 않을 때 Tomcat 스레드가 무한정
     * 묶여 커넥션 풀까지 고갈된다. 연결과 응답 모두 유한 상한을 둔다.
     */
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final PayPalCallBulkhead bulkhead;

    /** 호출 수 상한이 없는 클라이언트(시험과 직접 조립용). */
    public PayPalRestClient(RestClient.Builder builder, PayPalProperties properties,
            OAuth2AuthorizedClientManager authorizedClientManager, ObjectMapper objectMapper,
            Duration connectTimeout, Duration readTimeout) {
        this(builder, properties, authorizedClientManager, objectMapper, connectTimeout, readTimeout,
                new PayPalCallBulkhead(0, () -> { }));
    }

    @Autowired
    public PayPalRestClient(RestClient.Builder builder, PayPalProperties properties,
            OAuth2AuthorizedClientManager authorizedClientManager, ObjectMapper objectMapper,
            @Value("${atlas.payment.paypal.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${atlas.payment.paypal.read-timeout:PT10S}") Duration readTimeout,
            PayPalCallBulkhead bulkhead) {
        this.bulkhead = Objects.requireNonNull(bulkhead);
        this.builder = Objects.requireNonNull(builder).clone();
        this.properties = Objects.requireNonNull(properties);
        this.authorizedClientManager = Objects.requireNonNull(authorizedClientManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.connectTimeout = Objects.requireNonNull(connectTimeout);
        this.readTimeout = Objects.requireNonNull(readTimeout);
        this.requestFactory = createRequestFactory();
    }

    public OrderResult createOrder(String referenceId, BigDecimal amount, String currency,
            String returnUrl, String cancelUrl, String requestId) {
        Map<String, Object> unit = Map.of("reference_id", referenceId,
                "amount", Map.of("currency_code", currency, "value", amount.toPlainString()));
        Map<String, Object> body = Map.of("intent", "AUTHORIZE", "purchase_units", List.of(unit),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "return_url", returnUrl, "cancel_url", cancelUrl, "user_action", "PAY_NOW"))));
        Map<String, Object> response = post("/v2/checkout/orders", body, requestId);
        return new OrderResult(text(response, "id"), PayPalOrderStatus.from(text(response, "status")),
                approvalUrl(response));
    }

    public OrderStatus getOrder(String orderId) {
        Map<String, Object> response;
        try {
            response = bulkhead.call(() ->
                    api().get().uri("/v2/checkout/orders/{id}", orderId).retrieve().body(mapType()));
        } catch (RestClientException exception) {
            // 다른 호출과 같이 provider 장애로 감싼다. 감싸지 않으면 승인 반영 중 연결 시간 초과가
            // 500 으로 새어 나가고 결제 시도는 UNKNOWN 으로 기록되지 않아 복구 worker 가 재시도하지 못한다.
            throw new PayPalUnavailableException("PayPal order lookup failed", exception);
        }
        Map<String, Object> authorization = firstResource(response, "authorization");
        Map<String, Object> capture = firstResource(response, "capture");
        return new OrderStatus(text(response, "id"), PayPalOrderStatus.from(text(response, "status")),
                orderAmount(response), orderCurrency(response),
                authorization == null ? null : text(authorization, "id"),
                PayPalAuthorizationStatus.from(authorization == null ? null : text(authorization, "status")),
                capture == null ? null : text(capture, "id"),
                PayPalCaptureStatus.from(capture == null ? null : text(capture, "status")),
                resourceAmount(response, "capture"), resourceCurrency(response, "capture"),
                authorization == null ? null : instant(authorization, "expiration_time"));
    }

    public AuthorizationResult authorizeOrder(String orderId, String requestId) {
        Map<String, Object> response = post("/v2/checkout/orders/{id}/authorize".replace("{id}", orderId),
                Map.of(), requestId);
        Map<String, Object> authorization = firstResource(response, "authorization");
        return new AuthorizationResult(resourceId(response, "authorization"),
                PayPalAuthorizationStatus.from(resourceStatus(response, "authorization")),
                authorization == null ? null : instant(authorization, "expiration_time"));
    }

    public CaptureResult captureAuthorization(String authorizationId, BigDecimal amount,
            String currency, String requestId) {
        Map<String, Object> body = Map.of("amount", Map.of("currency_code", currency,
                "value", amount.toPlainString()), "final_capture", true);
        Map<String, Object> response = post("/v2/payments/authorizations/{id}/capture".replace("{id}", authorizationId),
                body, requestId, properties.getCaptureBaseUrl());
        return new CaptureResult(resourceId(response, "capture"),
                PayPalCaptureStatus.from(resourceStatus(response, "capture")),
                resourceAmount(response, "capture"), resourceCurrency(response, "capture"));
    }

    public void voidAuthorization(String authorizationId, String requestId) {
        try {
            bulkhead.run(() -> api().post().uri("/v2/payments/authorizations/{id}/void", authorizationId)
                    .header(REQUEST_ID, requestId).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of()).retrieve().toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw failure("PayPal authorization void failed", exception);
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal authorization void failed", exception);
        }
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
            Map<String, Object> response = bulkhead.call(() -> api().get().uri("/v2/payments/refunds/{id}", refundId)
                    .retrieve().body(mapType()));
            return new RefundResult(text(response, "id"), text(response, "status"),
                    resourceAmount(response, "refund"), resourceCurrency(response, "refund"));
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal refund lookup failed", exception);
        }
    }

    public WebhookVerificationResult verifyWebhook(PaymentWebhookSignatureRequest signature, String eventJson) {
        if (!properties.credentialsConfigured() || !properties.webhookConfigured()) {
            throw new PaymentException(PaymentException.Code.PAYMENT_PROVIDER_DISABLED,
                    "PayPal webhook credentials are not configured");
        }
        if (hasBlankSignatureField(signature)) {
            // 서명 헤더가 비면 PayPal 은 200 FAILURE 가 아니라 400 을 돌려준다(실제 Sandbox 확인,
            // EXP-081). 400 을 장애로 감싸면 위조 요청이 503 과 PayPal 호출을 만든다.
            return new WebhookVerificationResult(false);
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
        return new WebhookVerificationResult(PayPalWebhookVerificationStatus.from(
                text(response, "verification_status")) == PayPalWebhookVerificationStatus.SUCCESS);
    }

    private static boolean hasBlankSignatureField(PaymentWebhookSignatureRequest signature) {
        return isBlank(signature.authAlgorithm()) || isBlank(signature.certificateUrl())
                || isBlank(signature.transmissionId()) || isBlank(signature.transmissionSignature())
                || isBlank(signature.transmissionTime());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public PaymentWebhookEventResponse parseWebhook(String rawBody) {
        try {
            Map<String, Object> event = objectMapper.readValue(rawBody,
                    new TypeReference<Map<String, Object>>() { });
            Object rawResource = event.get("resource");
            Map<?, ?> resource = rawResource instanceof Map<?, ?> map ? map : Map.of();
            // 분쟁(CUSTOMER.DISPUTE.*) 자원은 id/amount 대신 dispute_id/dispute_amount 를 쓴다(customer_disputes_v1).
            Object rawAmount = resource.containsKey("amount") ? resource.get("amount") : resource.get("dispute_amount");
            Map<?, ?> amount = rawAmount instanceof Map<?, ?> map ? map : Map.of();
            Object rawSupplementary = resource.get("supplementary_data");
            Map<?, ?> supplementary = rawSupplementary instanceof Map<?, ?> map ? map : Map.of();
            Object rawRelatedIds = supplementary.get("related_ids");
            Map<?, ?> relatedIds = rawRelatedIds instanceof Map<?, ?> map ? map : Map.of();
            Object eventId = event.get("id");
            Object eventType = event.get("event_type");
            Object resourceId = resource.containsKey("id") ? resource.get("id") : resource.get("dispute_id");
            Object resourceStatus = resource.get("status");
            Object amountValue = amount.get("value");
            BigDecimal resourceAmount = amountValue instanceof String value
                    ? new BigDecimal(value) : null;
            Object resourceCurrency = amount.get("currency_code");
            Object providerOrderId = relatedIds.get("order_id");
            Object relatedCaptureId = resource.get("capture_id");
            if (relatedCaptureId == null) relatedCaptureId = relatedIds.get("capture_id");
            if (relatedCaptureId == null) relatedCaptureId = upCaptureId(resource.get("links"));
            if (relatedCaptureId == null) relatedCaptureId = disputedCaptureId(resource.get("disputed_transactions"));
            return new PaymentWebhookEventResponse(
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

    /**
     * refund 자원(PAYMENT.CAPTURE.REFUNDED/REVERSED, PAYMENT.REFUND.*)에는 capture_id 필드가 없고 HATEOAS
     * {@code rel=up} 링크만 capture 를 가리킨다(PayPal payments_payment_v2.json 의 capture_refunded 예시).
     */
    private static String upCaptureId(Object rawLinks) {
        if (!(rawLinks instanceof List<?> links)) return null;
        for (Object rawLink : links) {
            if (rawLink instanceof Map<?, ?> link && "up".equals(link.get("rel"))
                    && link.get("href") instanceof String href) {
                Matcher matcher = CAPTURE_LINK.matcher(href);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }

    /** 분쟁 자원은 거래를 disputed_transactions[].seller_transaction_id(판매자 쪽 capture id)로 가리킨다. 한 건만 받는다. */
    private static String disputedCaptureId(Object rawTransactions) {
        if (!(rawTransactions instanceof List<?> transactions) || transactions.size() != 1) return null;
        return transactions.getFirst() instanceof Map<?, ?> transaction
                && transaction.get("seller_transaction_id") instanceof String id ? id : null;
    }

    private Map<String, Object> postWebhook(Object body) {
        try {
            return bulkhead.call(() -> api().post().uri("/v1/notifications/verify-webhook-signature")
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(mapType()));
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal webhook verification failed", exception);
        }
    }

    private Map<String, Object> post(String uri, Object body, String requestId) {
        return post(uri, body, requestId, properties.getBaseUrl());
    }

    private Map<String, Object> post(String uri, Object body, String requestId, String baseUrl) {
        validateRequestId(requestId);
        try {
            // PayPal 기본값(return=minimal)은 capture·refund 응답에서 amount 를 빼므로, 성공한 capture 도
            // 금액 검증에 실패해 UNKNOWN 된다(실제 Sandbox 확인). 전체 표현을 명시적으로 요청한다.
            return bulkhead.call(() -> api(baseUrl).post().uri(uri).header(REQUEST_ID, requestId)
                    .header(PREFER, "return=representation")
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(mapType()));
        } catch (RestClientResponseException exception) {
            throw failure("PayPal API request failed", exception);
        } catch (RestClientException exception) {
            throw new PayPalUnavailableException("PayPal API request failed", exception);
        }
    }

    /**
     * HTTP 오류 응답을 예외로 바꾼다. 4xx 는 PayPal 오류 본문의 이슈를 담은 {@link PayPalRejectedException} 으로
     * 던지고, 그 밖(5xx, 3xx)은 이전처럼 장애 예외로 던진다. 거절 예외도 장애 예외의 하위 형식이라
     * 분류하지 않는 호출부는 지금처럼 결과 불명(UNKNOWN)으로 처리한다.
     */
    private PayPalUnavailableException failure(String message, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status < 400 || status >= 500) {
            return new PayPalUnavailableException(message, exception);
        }
        String issue = null;
        try {
            Map<String, Object> body = objectMapper.readValue(exception.getResponseBodyAsString(), mapType());
            issue = firstIssue(body);
        } catch (RuntimeException unparsable) {
            // PayPal 오류 본문이 아니면(프록시 HTML 등) 이슈 없이 둔다. 이슈가 없는 거절은 확정으로 보지 않는다.
        }
        return new PayPalRejectedException(message, exception, status, issue);
    }

    private static String firstIssue(Map<String, Object> body) {
        Object details = body == null ? null : body.get("details");
        if (!(details instanceof List<?> items) || items.isEmpty() || !(items.get(0) instanceof Map<?, ?> first)) {
            return null;
        }
        return text(castMap(first), "issue");
    }

    private RestClient api() { return api(properties.getBaseUrl()); }

    private RestClient api(String baseUrl) {
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
            // 요청별 인증 설정은 유지하되, 요청 팩토리는 이 클라이언트의 커넥션을 재사용한다.
            return builder.clone().baseUrl(trimSlash(baseUrl))
                    .requestFactory(requestFactory)
                    .defaultHeader("Authorization", "Bearer " + authorized.getAccessToken().getTokenValue())
                    .build();
        } catch (RestClientException | OAuth2AuthorizationException exception) {
            // 토큰 발급 실패(401 invalid_client, 5xx, 연결 실패)는 RestClientException 이 아니라
            // ClientAuthorizationException 으로 올라온다(실제 Sandbox 확인, EXP-081).
            throw new PayPalUnavailableException("PayPal OAuth request failed", exception);
        }
    }

    private JdkClientHttpRequestFactory createRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
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

    /** RFC 3339 시각. 형식이 다르면 null 이다(만료 시각은 표시용이라 못 읽어도 결제를 막지 않는다). */
    private static Instant instant(Map<String, Object> response, String key) {
        String value = text(response, key);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            return null;
        }
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

    public record OrderResult(String id, PayPalOrderStatus status, String approvalUrl) { }
    public record OrderStatus(String id, PayPalOrderStatus status, BigDecimal amount, String currency,
                              String authorizationId, PayPalAuthorizationStatus authorizationStatus,
                              String captureId, PayPalCaptureStatus captureStatus, BigDecimal captureAmount,
                              String captureCurrency, Instant authorizationExpiresAt) { }
    public record AuthorizationResult(String id, PayPalAuthorizationStatus status, Instant expiresAt) { }
    public record CaptureResult(String id, PayPalCaptureStatus status, BigDecimal amount, String currency) { }

    /** {@code providerStatus}는 환불 이력에 증거로 저장하는 PayPal 원문이다. */
    public record RefundResult(String id, String providerStatus, BigDecimal amount, String currency) {
        public PayPalRefundStatus status() { return PayPalRefundStatus.from(providerStatus); }
    }
    public record WebhookVerificationResult(boolean verified) { }

    public static class PayPalUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public PayPalUnavailableException(String message) { super(message); }
        public PayPalUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 요청 스레드의 동시 호출 상한을 넘어 {@code PayPalCallBulkhead} 가 보내기 전에 거절했다. 아무것도 보내지
     * 않았으므로 결과가 불명한 것이 아니라 확정된 미전송이다. 호출자는 장애 예외와 같게 처리해도 안전하다.
     */
    public static class PayPalBusyException extends PayPalUnavailableException {
        private static final long serialVersionUID = 1L;
        public PayPalBusyException(String message) { super(message); }
    }

    /**
     * PayPal 이 4xx 로 거절한 응답. 이 형식만으로는 결과를 확정하지 않는다. 어떤 (상태, 이슈) 쌍이
     * "작업이 일어나지 않았음"을 증명하는지는 호출 종류마다 달라 provider 가 정한다(ADR-0079).
     */
    public static class PayPalRejectedException extends PayPalUnavailableException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String issue;

        public PayPalRejectedException(String message, Throwable cause, int status, String issue) {
            super(message, cause);
            this.status = status;
            this.issue = issue;
        }

        public int status() { return status; }

        /** PayPal 오류 본문 {@code details[0].issue}. 본문이 PayPal 형식이 아니면 null. */
        public String issue() { return issue; }

        public boolean is(int expectedStatus, String expectedIssue) {
            return status == expectedStatus && expectedIssue.equals(issue);
        }
    }
}
