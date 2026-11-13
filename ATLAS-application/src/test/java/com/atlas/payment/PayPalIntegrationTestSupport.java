package com.atlas.payment;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.SocketPolicy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 통합 테스트가 샌드박스나 실제 자격 증명에 접근하지 않도록 로컬 PayPal API를 대체한다. */
public abstract class PayPalIntegrationTestSupport {
    protected static final MockWebServer PAYPAL = new MockWebServer();
    protected static final MockWebServer ECOS = new MockWebServer();
    private static final Map<String, Amount> ORDERS = new ConcurrentHashMap<>();
    private static final Map<String, Amount> AUTHORIZATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Amount> CAPTURES = new ConcurrentHashMap<>();
    private static final Map<String, String> REFUNDS_BY_REQUEST_ID = new ConcurrentHashMap<>();
    private static final Map<String, Amount> REFUND_AMOUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> CAPTURE_IDS_BY_REQUEST_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> CAPTURE_IDS_BY_ORDER_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> AUTHORIZATION_ORDER_IDS = new ConcurrentHashMap<>();
    private static final Queue<String> AUTHORIZATION_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_CREATE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_APPROVAL_LINK_RELATIONS = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_AUTHORIZATION_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_CAPTURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> CAPTURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> VOID_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> REFUND_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> WEBHOOK_SIGNATURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DISCONNECT_NEXT_CAPTURE = new AtomicBoolean();
    private static final AtomicBoolean DISCONNECT_NEXT_AUTHORIZATION = new AtomicBoolean();
    private static final AtomicInteger CAPTURE_REQUEST_COUNT = new AtomicInteger();
    protected static void queueAuthorizationStatus(String status) { AUTHORIZATION_STATUSES.add(status); }
    protected static void queueOrderStatus(String status) { ORDER_STATUSES.add(status); }
    protected static void queueOrderCreateStatus(String status) { ORDER_CREATE_STATUSES.add(status); }
    protected static void queueOrderApprovalLinkRelation(String relation) {
        ORDER_APPROVAL_LINK_RELATIONS.add(relation);
    }
    protected static void queueOrderAuthorizationStatus(String status) { ORDER_AUTHORIZATION_STATUSES.add(status); }
    protected static void queueOrderCaptureStatus(String status) { ORDER_CAPTURE_STATUSES.add(status); }
    protected static void queueCaptureStatus(String status) { CAPTURE_STATUSES.add(status); }
    protected static void queueVoidStatus(String status) { VOID_STATUSES.add(status); }
    protected static void queueRefundStatus(String status) { REFUND_STATUSES.add(status); }
    protected static void queueWebhookVerificationStatus(String status) { WEBHOOK_SIGNATURE_STATUSES.add(status); }
    protected static void disconnectNextCaptureResponse() { DISCONNECT_NEXT_CAPTURE.set(true); }
    protected static void disconnectNextAuthorizationResponse() { DISCONNECT_NEXT_AUTHORIZATION.set(true); }
    protected static int captureRequestCount() { return CAPTURE_REQUEST_COUNT.get(); }

    @BeforeEach
    protected void resetPayPalStubState() throws InterruptedException {
        ORDERS.clear();
        AUTHORIZATIONS.clear();
        CAPTURES.clear();
        REFUNDS_BY_REQUEST_ID.clear();
        REFUND_AMOUNTS.clear();
        CAPTURE_IDS_BY_REQUEST_ID.clear();
        CAPTURE_IDS_BY_ORDER_ID.clear();
        AUTHORIZATION_ORDER_IDS.clear();
        AUTHORIZATION_STATUSES.clear();
        ORDER_STATUSES.clear();
        ORDER_CREATE_STATUSES.clear();
        ORDER_APPROVAL_LINK_RELATIONS.clear();
        ORDER_AUTHORIZATION_STATUSES.clear();
        ORDER_CAPTURE_STATUSES.clear();
        CAPTURE_STATUSES.clear();
        VOID_STATUSES.clear();
        REFUND_STATUSES.clear();
        WEBHOOK_SIGNATURE_STATUSES.clear();
        DISCONNECT_NEXT_CAPTURE.set(false);
        DISCONNECT_NEXT_AUTHORIZATION.set(false);
        CAPTURE_REQUEST_COUNT.set(0);
        while (PAYPAL.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // 이전 테스트의 미소비 요청을 제거한다.
        }
        while (ECOS.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // 이전 테스트의 ECOS 요청을 제거한다.
        }
    }
    private static volatile boolean started;
    static {
        try {
            startPayPalStub();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                PAYPAL.shutdown();
            } catch (IOException ignored) {
                // JVM 종료 중인 test server 정리 실패는 후속 동작에 영향 없음.
            }
            try {
                ECOS.shutdown();
            } catch (IOException ignored) {
                // JVM 종료 중인 test server 정리 실패는 후속 동작에 영향 없음.
            }
        }));
    }

    static synchronized void startPayPalStub() throws IOException {
        if (started) {
            return;
        }
        PAYPAL.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath().split("\\?", 2)[0];
                String method = request.getMethod();
                String body = request.getBody().clone().readUtf8();
                if (path.equals("/v1/oauth2/token")) {
                    return json("{\"access_token\":\"local-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                }
                if (path.equals("/v2/checkout/orders") && "POST".equals(method)) {
                    String id = "ORDER-" + UUID.randomUUID();
                    Amount amount = Amount.from(body);
                    ORDERS.put(id, amount);
                    String status = nextStatus(ORDER_CREATE_STATUSES, "CREATED");
                    String approvalRelation = nextStatus(ORDER_APPROVAL_LINK_RELATIONS,
                            "PAYER_ACTION_REQUIRED".equals(status) ? "payer-action" : "approve");
                    return json("{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"links\":[{\"rel\":\""
                            + approvalRelation + "\",\"href\":\"http://localhost/approve/" + id + "\",\"method\":\"GET\"}]}");
                }
                if (path.matches("/v2/checkout/orders/[^/]+/authorize") && "POST".equals(method)) {
                    String orderId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount amount = ORDERS.getOrDefault(orderId, Amount.from(body));
                    String authId = "AUTH-" + UUID.randomUUID();
                    AUTHORIZATIONS.put(authId, amount);
                    AUTHORIZATION_ORDER_IDS.put(authId, orderId);
                    if (DISCONNECT_NEXT_AUTHORIZATION.compareAndSet(true, false)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    return authorized(orderId, authId, amount, nextStatus(AUTHORIZATION_STATUSES, "CREATED"));
                }
                if (path.matches("/v2/checkout/orders/[^/]+/capture") && "POST".equals(method)) {
                    String orderId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount amount = ORDERS.getOrDefault(orderId, Amount.from(body));
                    String captureId = captureId(request);
                    CAPTURES.put(captureId, amount);
                    CAPTURE_IDS_BY_ORDER_ID.put(orderId, captureId);
                    CAPTURE_REQUEST_COUNT.incrementAndGet();
                    if (DISCONNECT_NEXT_CAPTURE.compareAndSet(true, false)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    return captured(orderId, captureId, amount, nextStatus(CAPTURE_STATUSES, "COMPLETED"));
                }
                if (path.matches("/v2/checkout/orders/[^/]+") && "GET".equals(method)) {
                    String orderId = lastSegment(path);
                    Amount amount = ORDERS.getOrDefault(orderId, Amount.from(body));
                    String capturedId = CAPTURE_IDS_BY_ORDER_ID.get(orderId);
                    if (capturedId != null) {
                        return captured(orderId, capturedId, CAPTURES.getOrDefault(capturedId, amount), "COMPLETED");
                    }
                    String orderStatus = nextStatus(ORDER_STATUSES, "APPROVED");
                    String authorizationStatus = nextStatus(ORDER_AUTHORIZATION_STATUSES, null);
                    String captureStatus = nextStatus(ORDER_CAPTURE_STATUSES,
                            "COMPLETED".equals(orderStatus) ? "COMPLETED" : null);
                    String resources = "";
                    if (authorizationStatus != null && !"NONE".equals(authorizationStatus)) {
                        resources = "\"authorizations\":[{\"id\":\"AUTH-" + orderId
                                + "\",\"status\":\"" + authorizationStatus + "\"}]";
                    }
                    if (captureStatus != null && !"NONE".equals(captureStatus)) {
                        String capture = "\"captures\":[{\"id\":\"CAPTURE-" + orderId
                                + "\",\"status\":\"" + captureStatus + "\",\"amount\":{\"currency_code\":\""
                                + amount.currency + "\",\"value\":\"" + amount.value + "\"}}]";
                        resources = resources.isEmpty() ? capture : resources + "," + capture;
                    }
                    String payments = resources.isEmpty() ? ""
                            : ",\"payments\":{" + resources + "}";
                    String unit = "{\"amount\":{\"currency_code\":\"" + amount.currency
                            + "\",\"value\":\"" + amount.value + "\"}" + payments + "}";
                    return json("{\"id\":\"" + orderId + "\",\"status\":\"" + orderStatus
                            + "\",\"purchase_units\":[" + unit + "]}");
                }
                if (path.matches("/v2/payments/authorizations/[^/]+/capture") && "POST".equals(method)) {
                    String authId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount amount = AUTHORIZATIONS.getOrDefault(authId, Amount.from(body));
                    String captureId = captureId(request);
                    String orderId = AUTHORIZATION_ORDER_IDS.get(authId);
                    if (orderId != null) CAPTURE_IDS_BY_ORDER_ID.put(orderId, captureId);
                    CAPTURES.put(captureId, amount);
                    CAPTURE_REQUEST_COUNT.incrementAndGet();
                    if (DISCONNECT_NEXT_CAPTURE.compareAndSet(true, false)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    return captureResource(captureId, amount, nextStatus(CAPTURE_STATUSES, "COMPLETED"));
                }
                if (path.matches("/v2/payments/authorizations/[^/]+/void") && "POST".equals(method)) {
                    String authId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    return json("{\"id\":\"" + authId + "\",\"status\":\"" + nextStatus(VOID_STATUSES, "VOIDED") + "\"}");
                }
                if (path.matches("/v2/payments/captures/[^/]+/refund") && "POST".equals(method)) {
                    String captureId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount amount = Amount.from(body, CAPTURES.getOrDefault(captureId, Amount.ZERO));
                    String requestId = request.getHeader("PayPal-Request-Id");
                    String replayKey = requestId == null ? UUID.randomUUID().toString() : requestId;
                    String refundId = REFUNDS_BY_REQUEST_ID.computeIfAbsent(replayKey,
                            ignored -> "REFUND-" + UUID.randomUUID());
                    REFUND_AMOUNTS.put(refundId, amount);
                    String refundStatus = nextStatus(REFUND_STATUSES, "COMPLETED");
                    return json("{\"id\":\"" + refundId + "\",\"status\":\"" + refundStatus + "\",\"amount\":{\"currency_code\":\"" + amount.currency + "\",\"value\":\"" + amount.value + "\"}}");
                }
                if (path.matches("/v2/payments/refunds/[^/]+") && "GET".equals(method)) {
                    String refundId = lastSegment(path);
                    Amount amount = REFUND_AMOUNTS.get(refundId);
                    if (amount == null) {
                        return json("{\"id\":\"" + refundId + "\",\"status\":\"COMPLETED\"}");
                    }
                    return json("{\"id\":\"" + refundId + "\",\"status\":\"COMPLETED\",\"amount\":{\"currency_code\":\""
                            + amount.currency + "\",\"value\":\"" + amount.value + "\"}}");
                }
                if (path.equals("/v1/notifications/verify-webhook-signature") && "POST".equals(method)) {
                    return json("{\"verification_status\":\""
                            + nextStatus(WEBHOOK_SIGNATURE_STATUSES, "SUCCESS") + "\"}");
                }
                return new MockResponse().setResponseCode(404).setBody("{\"error\":\"unexpected local PayPal path\"}");
            }
        });
        PAYPAL.start();
        ECOS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String date = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
                return json("{\"StatisticSearch\":{\"row\":[{\"TIME\":\"" + date
                        + "\",\"DATA_VALUE\":\"1332.4\"}]}}");
            }
        });
        ECOS.start();
        started = true;
    }

    @DynamicPropertySource
    protected static void paypalTestProperties(DynamicPropertyRegistry registry) {
        registry.add("atlas.payment.provider", () -> "paypal");
        registry.add("ATLAS_PUBLIC_BASE_URL", () -> "http://127.0.0.1:18080");
        registry.add("atlas.payment.paypal.base-url", () -> PAYPAL.url("/").toString());
        registry.add("atlas.payment.bok-ecos.base-url", () -> ECOS.url("/").toString());
        registry.add("BOK_ECOS_API_KEY", () -> "local-test-key");
        registry.add("atlas.payment.paypal.client-id", () -> "local-test-client");
        registry.add("atlas.payment.paypal.client-secret", () -> "local-test-secret");
        registry.add("atlas.payment.paypal.webhook-id", () -> "local-test-webhook");
        registry.add("PAYPAL_BASE_URL", () -> PAYPAL.url("/").toString());
        registry.add("PAYPAL_CLIENT_ID", () -> "local-test-client");
        registry.add("PAYPAL_CLIENT_SECRET", () -> "local-test-secret");
        registry.add("PAYPAL_WEBHOOK_ID", () -> "local-test-webhook");
        registry.add("spring.security.oauth2.client.registration.paypal.client-id", () -> "local-test-client");
        registry.add("spring.security.oauth2.client.registration.paypal.client-secret", () -> "local-test-secret");
    }

    protected RecordedRequest takePayPalRequest() throws InterruptedException {
        return PAYPAL.takeRequest(2, TimeUnit.SECONDS);
    }
    protected static String approvalToken(String paymentApprovalUrl) {
        return paymentApprovalUrl.substring(paymentApprovalUrl.lastIndexOf('/') + 1);
    }

    protected static MockResponse json(String value) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(value);
    }

    private static MockResponse authorized(String order, String auth, Amount amount, String status) {
        return json("{\"id\":\"" + order + "\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"payments\":{\"authorizations\":[{\"id\":\"" + auth + "\",\"status\":\"" + status + "\",\"amount\":{\"currency_code\":\"" + amount.currency + "\",\"value\":\"" + amount.value + "\"}}]}}]}");
    }

    private static MockResponse captured(String order, String capture, Amount amount, String status) {
        return json("{\"id\":\"" + order
                + "\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"amount\":{\"currency_code\":\""
                + amount.currency + "\",\"value\":\"" + amount.value
                + "\"},\"payments\":{\"captures\":[{\"id\":\"" + capture
                + "\",\"status\":\"" + status + "\",\"amount\":{\"currency_code\":\""
                + amount.currency + "\",\"value\":\"" + amount.value + "\"}}]}}]}");
    }
    private static MockResponse captureResource(String capture, Amount amount, String status) {
        return json("{\"id\":\"" + capture + "\",\"status\":\"" + status
                + "\",\"amount\":{\"currency_code\":\"" + amount.currency
                + "\",\"value\":\"" + amount.value + "\"}}");
    }

    private static String captureId(RecordedRequest request) {
        String requestId = request.getHeader("PayPal-Request-Id");
        String key = requestId == null ? UUID.randomUUID().toString() : requestId;
        return CAPTURE_IDS_BY_REQUEST_ID.computeIfAbsent(key, ignored -> "CAPTURE-" + UUID.randomUUID());
    }

    private static String nextStatus(Queue<String> statuses, String fallback) {
        String status = statuses.poll();
        return status == null ? fallback : status;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record Amount(String currency, String value) {
        private static final Amount ZERO = new Amount("USD", "0.00");
        private static final Pattern CURRENCY = Pattern.compile("\\\"currency_code\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        private static final Pattern VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

        private static Amount from(String body) {
            return from(body, ZERO);
        }

        private static Amount from(String body, Amount fallback) {
            Matcher currencyMatcher = CURRENCY.matcher(body);
            Matcher valueMatcher = VALUE.matcher(body);
            return new Amount(currencyMatcher.find() ? currencyMatcher.group(1) : fallback.currency,
                    valueMatcher.find() ? valueMatcher.group(1) : fallback.value);
        }
    }
}
