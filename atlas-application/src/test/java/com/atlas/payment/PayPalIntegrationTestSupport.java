package com.atlas.payment;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.SocketPolicy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import com.atlas.payment.provider.exchange.CachingExchangeRateProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 통합 테스트가 샌드박스나 실제 자격 증명에 접근하지 않도록 로컬 PayPal API를 대체한다. */
public abstract class PayPalIntegrationTestSupport {
    protected static final MockWebServer PAYPAL = new MockWebServer();
    /** 실제 Sandbox authorize 응답 값(EXP-086: 생성 2026-11-25T01:19:22Z + 29일). */
    protected static final String AUTHORIZATION_EXPIRATION = "2026-12-24T01:19:22Z";
    protected static final MockWebServer ECOS = new MockWebServer();
    private static final Map<String, Amount> ORDERS = new ConcurrentHashMap<>();
    /** 실제 PayPal 처럼 같은 PayPal-Request-Id 로 생성·승인 요청을 다시 보내면 같은 리소스를 돌려준다. */
    private static final Map<String, String> ORDER_IDS_BY_REQUEST_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> AUTHORIZATION_IDS_BY_REQUEST_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> CAPTURE_IDS_BY_AUTHORIZATION_ID = new ConcurrentHashMap<>();
    private static final Map<String, BigDecimal> REFUNDED_TOTALS = new ConcurrentHashMap<>();
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
    private static final Queue<String> ORDER_AUTHORIZATION_EXPIRATIONS = new ConcurrentLinkedQueue<>();
    private static final Queue<String> ORDER_CAPTURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> CAPTURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> VOID_STATUSES = new ConcurrentLinkedQueue<>();
    /** 호출 종류별로 다음 요청에 줄 4xx·5xx 거절 큐. 값은 "상태코드:issue" 형식이다. */
    private static final Map<String, Queue<String>> REJECTIONS = new ConcurrentHashMap<>();
    private static final Queue<String> REFUND_STATUSES = new ConcurrentLinkedQueue<>();
    private static final Queue<String> WEBHOOK_SIGNATURE_STATUSES = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DISCONNECT_NEXT_CAPTURE = new AtomicBoolean();
    private static final AtomicBoolean DISCONNECT_NEXT_AUTHORIZATION = new AtomicBoolean();
    private static final AtomicBoolean DISCONNECT_NEXT_REFUND = new AtomicBoolean();
    private static final AtomicInteger CAPTURE_REQUEST_COUNT = new AtomicInteger();
    /** 외부 환율 API 호출 수. 결제 임계 경로의 외부 의존 횟수를 세는 데 쓴다. */
    private static final AtomicInteger EXCHANGE_RATE_REQUESTS = new AtomicInteger();
    /** PayPal stub 이 받은 요청을 경로별로 센다. 중복 provider 호출 진단에 쓴다. */
    private static final Map<String, Integer> PROVIDER_CALLS =
            new ConcurrentHashMap<>();
    private static final Queue<String> REFUND_REQUEST_IDS = new ConcurrentLinkedQueue<>();

    /**
     * 다음 create-order 호출 하나를 붙잡는다. 호출이 PayPal stub 에 도착하면 {@code arrived}가 풀리고,
     * 시험이 {@code release}를 풀기 전까지 응답하지 않아 그 호출을 보낸 스레드가 묶인 상태를 만든다.
     */
    protected record Hold(CountDownLatch arrived, CountDownLatch release) { }

    private static final AtomicReference<Hold> CREATE_ORDER_HOLD = new AtomicReference<>();

    protected static Hold holdNextCreateOrder() {
        Hold hold = new Hold(new CountDownLatch(1), new CountDownLatch(1));
        CREATE_ORDER_HOLD.set(hold);
        return hold;
    }
    protected static void queueAuthorizationStatus(String status) { AUTHORIZATION_STATUSES.add(status); }
    protected static void queueOrderStatus(String status) { ORDER_STATUSES.add(status); }
    protected static void queueOrderCreateStatus(String status) { ORDER_CREATE_STATUSES.add(status); }
    protected static void queueOrderApprovalLinkRelation(String relation) {
        ORDER_APPROVAL_LINK_RELATIONS.add(relation);
    }
    protected static void queueOrderAuthorizationStatus(String status) { ORDER_AUTHORIZATION_STATUSES.add(status); }
    protected static void queueOrderAuthorizationExpiration(String expirationTime) {
        ORDER_AUTHORIZATION_EXPIRATIONS.add(expirationTime);
    }
    protected static void queueOrderCaptureStatus(String status) { ORDER_CAPTURE_STATUSES.add(status); }
    protected static void queueCaptureStatus(String status) { CAPTURE_STATUSES.add(status); }
    protected static void queueVoidStatus(String status) { VOID_STATUSES.add(status); }

    /**
     * 다음 {@code operation} 요청 하나에 실제 PayPal 오류 본문(EXP-081 모양)을 준다.
     * operation: create-order, authorize, capture, void.
     */
    protected static void queueRejection(String operation, int status, String issue) {
        REJECTIONS.computeIfAbsent(operation, ignored -> new ConcurrentLinkedQueue<>())
                .add(status + ":" + issue);
    }
    protected static void queueRefundStatus(String status) { REFUND_STATUSES.add(status); }
    protected static void queueWebhookVerificationStatus(String status) { WEBHOOK_SIGNATURE_STATUSES.add(status); }
    protected static void disconnectNextCaptureResponse() { DISCONNECT_NEXT_CAPTURE.set(true); }
    protected static void disconnectNextAuthorizationResponse() { DISCONNECT_NEXT_AUTHORIZATION.set(true); }
    /** 다음 refund POST를 stub에 기록한 뒤 응답 없이 연결을 끊는다. */
    protected static void disconnectNextRefundResponse() { DISCONNECT_NEXT_REFUND.set(true); }
    protected static int captureRequestCount() { return CAPTURE_REQUEST_COUNT.get(); }
    protected static int exchangeRateRequestCount() { return EXCHANGE_RATE_REQUESTS.get(); }

    /** 경로별 provider 호출 수 스냅샷. */
    protected static Map<String, Integer> providerCalls() {
        return Map.copyOf(PROVIDER_CALLS);
    }

    /** 테스트 JVM 은 -D 를 상속하지 않으므로 환경변수로 지연을 주입한다. */
    private static long parseEnvLong(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException invalid) {
            return 0L;
        }
    }
    protected static List<String> refundRequestIds() { return List.copyOf(REFUND_REQUEST_IDS); }

    @Autowired(required = false)
    private CachingExchangeRateProvider fxCacheUnderTest;

    @BeforeEach
    protected void resetPayPalStubState() throws InterruptedException {
        // 결제 테스트는 Spring context 를 공유한다. 앞선 테스트가 데운 환율 캐시를 비우지 않으면
        // "결제 1건이 외부 호출 1회"라는 계약이 테스트 순서에 의존하게 된다.
        CREATE_ORDER_HOLD.set(null);
        if (fxCacheUnderTest != null) fxCacheUnderTest.clear();
        ORDERS.clear();
        ORDER_IDS_BY_REQUEST_ID.clear();
        AUTHORIZATION_IDS_BY_REQUEST_ID.clear();
        CAPTURE_IDS_BY_AUTHORIZATION_ID.clear();
        REFUNDED_TOTALS.clear();
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
        ORDER_AUTHORIZATION_EXPIRATIONS.clear();
        ORDER_CAPTURE_STATUSES.clear();
        CAPTURE_STATUSES.clear();
        VOID_STATUSES.clear();
        REJECTIONS.clear();
        REFUND_STATUSES.clear();
        WEBHOOK_SIGNATURE_STATUSES.clear();
        DISCONNECT_NEXT_CAPTURE.set(false);
        DISCONNECT_NEXT_AUTHORIZATION.set(false);
        DISCONNECT_NEXT_REFUND.set(false);
        CAPTURE_REQUEST_COUNT.set(0);
        EXCHANGE_RATE_REQUESTS.set(0);
        PROVIDER_CALLS.clear();
        REFUND_REQUEST_IDS.clear();
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
                PROVIDER_CALLS.merge(method + " " + path, 1, Integer::sum);
                if (path.equals("/v1/oauth2/token")) {
                    return json("{\"access_token\":\"local-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                }
                if (path.equals("/v2/checkout/orders") && "POST".equals(method)) {
                    Hold hold = CREATE_ORDER_HOLD.getAndSet(null);
                    if (hold != null) {
                        hold.arrived().countDown();
                        try {
                            hold.release().await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    MockResponse rejected = pollRejection("create-order");
                    if (rejected != null) return rejected;
                    String requestId = request.getHeader("PayPal-Request-Id");
                    // 실제 Sandbox: 같은 Request-Id 는 본문이 달라도 처음 만든 주문을 돌려준다(EXP-081).
                    String id = requestId == null ? newOrderId()
                            : ORDER_IDS_BY_REQUEST_ID.computeIfAbsent(requestId, ignored -> newOrderId());
                    ORDERS.computeIfAbsent(id, ignored -> Amount.from(body));
                    // ATLAS 는 payment_source 를 보내므로 실제 응답은 HTTP 200, PAYER_ACTION_REQUIRED, payer-action 이다.
                    String status = nextStatus(ORDER_CREATE_STATUSES, "PAYER_ACTION_REQUIRED");
                    String approvalRelation = nextStatus(ORDER_APPROVAL_LINK_RELATIONS,
                            "PAYER_ACTION_REQUIRED".equals(status) ? "payer-action" : "approve");
                    return json("{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"links\":[{\"rel\":\""
                            + approvalRelation + "\",\"href\":\"http://localhost/approve/" + id + "\",\"method\":\"GET\"}]}");
                }
                if (path.matches("/v2/checkout/orders/[^/]+/authorize") && "POST".equals(method)) {
                    String orderId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount amount = ORDERS.get(orderId);
                    if (amount == null) return resourceNotFound();
                    MockResponse rejected = pollRejection("authorize");
                    if (rejected != null) return rejected;
                    String requestId = request.getHeader("PayPal-Request-Id");
                    String authId;
                    synchronized (STUB_LOCK) {
                        authId = requestId == null ? null : AUTHORIZATION_IDS_BY_REQUEST_ID.get(requestId);
                        if (authId == null) {
                            // 실제 Sandbox: 이미 승인한 주문을 다른 Request-Id 로 다시 승인하면 422 다(EXP-081).
                            if (AUTHORIZATION_ORDER_IDS.containsValue(orderId)) {
                                return unprocessable("ORDER_ALREADY_AUTHORIZED", "Authorization has already been created for this order.");
                            }
                            authId = "AUTH-" + UUID.randomUUID();
                            if (requestId != null) AUTHORIZATION_IDS_BY_REQUEST_ID.put(requestId, authId);
                            AUTHORIZATION_ORDER_IDS.put(authId, orderId);
                        }
                    }
                    AUTHORIZATIONS.put(authId, amount);
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
                    Amount amount = ORDERS.get(orderId);
                    if (amount == null) return resourceNotFound();
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
                        resources = "\"authorizations\":[{\"id\":\"" + authorizationIdOf(orderId)
                                + "\",\"status\":\"" + authorizationStatus + "\",\"expiration_time\":\""
                                + nextStatus(ORDER_AUTHORIZATION_EXPIRATIONS, AUTHORIZATION_EXPIRATION) + "\"}]";
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
                    Amount amount = AUTHORIZATIONS.get(authId);
                    if (amount == null) return resourceNotFound();
                    MockResponse rejected = pollRejection("capture");
                    if (rejected != null) return rejected;
                    String captureId = captureId(request);
                    synchronized (STUB_LOCK) {
                        // 실제 Sandbox: 이미 캡처한 승인을 다른 Request-Id 로 다시 캡처하면 422 다(EXP-081).
                        String existing = CAPTURE_IDS_BY_AUTHORIZATION_ID.putIfAbsent(authId, captureId);
                        if (existing != null && !existing.equals(captureId)) {
                            return unprocessable("AUTHORIZATION_ALREADY_CAPTURED", "Authorization has already been captured.");
                        }
                    }
                    String orderId = AUTHORIZATION_ORDER_IDS.get(authId);
                    if (orderId != null) CAPTURE_IDS_BY_ORDER_ID.put(orderId, captureId);
                    CAPTURES.put(captureId, amount);
                    CAPTURE_REQUEST_COUNT.incrementAndGet();
                    if (DISCONNECT_NEXT_CAPTURE.compareAndSet(true, false)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    String captureStatus = nextStatus(CAPTURE_STATUSES, "COMPLETED");
                    // 실제 PayPal 처럼 return=representation 없이는 amount 를 주지 않는다.
                    if (!wantsRepresentation(request)) return minimal(captureId, captureStatus);
                    return captureResource(captureId, amount, captureStatus);
                }
                if (path.matches("/v2/payments/authorizations/[^/]+/void") && "POST".equals(method)) {
                    String authId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    if (!AUTHORIZATIONS.containsKey(authId)) return resourceNotFound();
                    MockResponse rejected = pollRejection("void");
                    if (rejected != null) return rejected;
                    return json("{\"id\":\"" + authId + "\",\"status\":\"" + nextStatus(VOID_STATUSES, "VOIDED") + "\"}");
                }
                if (path.matches("/v2/payments/captures/[^/]+/refund") && "POST".equals(method)) {
                    String captureId = lastSegment(path.substring(0, path.lastIndexOf('/')));
                    Amount captured = CAPTURES.get(captureId);
                    if (captured == null) return resourceNotFound();
                    Amount amount = Amount.from(body, captured);
                    String requestId = request.getHeader("PayPal-Request-Id");
                    String replayKey = requestId == null ? UUID.randomUUID().toString() : requestId;
                    String refundId;
                    synchronized (STUB_LOCK) {
                        if (!REFUNDS_BY_REQUEST_ID.containsKey(replayKey)) {
                            // 실제 Sandbox: 환불 누계가 캡처 금액을 넘으면 REFUND_AMOUNT_EXCEEDED,
                            // 전액 환불된 캡처는 CAPTURE_FULLY_REFUNDED 로 422 다(EXP-081).
                            BigDecimal capturedValue = new BigDecimal(captured.value);
                            BigDecimal refundedSoFar = REFUNDED_TOTALS.getOrDefault(captureId, BigDecimal.ZERO);
                            if (refundedSoFar.compareTo(capturedValue) >= 0) {
                                return unprocessable("CAPTURE_FULLY_REFUNDED", "The capture has already been fully refunded.");
                            }
                            BigDecimal requested = body.contains("\"value\"")
                                    ? new BigDecimal(amount.value) : capturedValue.subtract(refundedSoFar);
                            if (refundedSoFar.add(requested).compareTo(capturedValue) > 0) {
                                return unprocessable("REFUND_AMOUNT_EXCEEDED",
                                        "The refund amount must be less than or equal to the capture amount that has not yet been refunded.");
                            }
                            REFUNDED_TOTALS.put(captureId, refundedSoFar.add(requested));
                        }
                        refundId = REFUNDS_BY_REQUEST_ID.computeIfAbsent(replayKey, ignored -> "REFUND-" + UUID.randomUUID());
                    }
                    REFUND_REQUEST_IDS.add(replayKey);
                    REFUND_AMOUNTS.put(refundId, amount);
                    if (DISCONNECT_NEXT_REFUND.compareAndSet(true, false)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    String refundStatus = nextStatus(REFUND_STATUSES, "COMPLETED");
                    if (!wantsRepresentation(request)) return minimal(refundId, refundStatus);
                    return json("{\"id\":\"" + refundId + "\",\"status\":\"" + refundStatus + "\",\"amount\":{\"currency_code\":\"" + amount.currency + "\",\"value\":\"" + amount.value + "\"}}");
                }
                if (path.matches("/v2/payments/refunds/[^/]+") && "GET".equals(method)) {
                    String refundId = lastSegment(path);
                    Amount amount = REFUND_AMOUNTS.get(refundId);
                    if (amount == null) return resourceNotFound();
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
                EXCHANGE_RATE_REQUESTS.incrementAndGet();
                // 외부 환율 API 의 네트워크 지연을 주입해 결제 임계 경로의 영향을 잰다.
                long injected = parseEnvLong("FX_STUB_LATENCY_MS");
                if (injected > 0) {
                    try {
                        Thread.sleep(injected);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
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
        registry.add("atlas.payment.paypal.local-proxy-enabled", () -> true);
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

    private static final Object STUB_LOCK = new Object();

    private static String newOrderId() {
        return "ORDER-" + UUID.randomUUID();
    }

    /** 이 주문에 대해 stub 이 발급한 승인 ID. 없으면(승인 전 조회) 주문 ID 로 만든 임의 값. */
    private static String authorizationIdOf(String orderId) {
        return AUTHORIZATION_ORDER_IDS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(orderId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("AUTH-" + orderId);
    }

    /**
     * 실제 PayPal 오류 본문 모양(name, message, debug_id, details[], links[])이다.
     * 실제 Sandbox 와 대조한 근거는 EXP-081.
     */
    private static MockResponse paypalError(int status, String name, String message, String issue,
            String description) {
        return new MockResponse().setResponseCode(status).addHeader("Content-Type", "application/json")
                .setBody("{\"name\":\"" + name + "\",\"message\":\"" + message
                        + "\",\"debug_id\":\"local-debug-id\",\"details\":[{\"issue\":\"" + issue
                        + "\",\"description\":\"" + description + "\"}],\"links\":[{\"href\":"
                        + "\"https://developer.paypal.com/api/rest/reference/orders/v2/errors/#" + issue
                        + "\",\"rel\":\"information_link\",\"method\":\"GET\"}]}");
    }

    /** 처음 보는 ID 를 조회하거나 쓰면 실제 PayPal 은 404 RESOURCE_NOT_FOUND 를 준다. */
    private static MockResponse resourceNotFound() {
        return paypalError(404, "RESOURCE_NOT_FOUND", "The specified resource does not exist.",
                "INVALID_RESOURCE_ID", "Specified resource ID does not exist. Please check the resource ID and try again.");
    }

    /** 큐에 넣은 거절이 있으면 PayPal 오류 본문으로 만들어 돌려주고, 없으면 null 이다. */
    private static MockResponse pollRejection(String operation) {
        Queue<String> queue = REJECTIONS.get(operation);
        String next = queue == null ? null : queue.poll();
        if (next == null) return null;
        int status = Integer.parseInt(next.substring(0, next.indexOf(':')));
        String issue = next.substring(next.indexOf(':') + 1);
        String name = switch (status) {
            case 401 -> "AUTHENTICATION_FAILURE";
            case 429 -> "RATE_LIMIT_REACHED";
            case 500, 503 -> "INTERNAL_SERVER_ERROR";
            case 422 -> "UNPROCESSABLE_ENTITY";
            default -> "INVALID_REQUEST";
        };
        return paypalError(status, name, "local rejection " + status, issue, "local rejection " + issue);
    }

    private static MockResponse unprocessable(String issue, String description) {
        return paypalError(422, "UNPROCESSABLE_ENTITY",
                "The requested action could not be performed, semantically incorrect, or failed business validation.",
                issue, description);
    }

    protected static MockResponse json(String value) {
        return new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(value);
    }

    private static MockResponse authorized(String order, String auth, Amount amount, String status) {
        return json("{\"id\":\"" + order + "\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"payments\":{\"authorizations\":[{\"id\":\"" + auth + "\",\"status\":\"" + status + "\",\"expiration_time\":\"" + AUTHORIZATION_EXPIRATION + "\",\"amount\":{\"currency_code\":\"" + amount.currency + "\",\"value\":\"" + amount.value + "\"}}]}}]}");
    }

    private static MockResponse captured(String order, String capture, Amount amount, String status) {
        // 실제 PayPal 은 캡처 뒤에도 authorizations[](상태 CAPTURED)를 captures[] 와 함께 준다(EXP-081).
        String authorizations = AUTHORIZATION_ORDER_IDS.containsValue(order)
                ? "\"authorizations\":[{\"id\":\"" + authorizationIdOf(order)
                        + "\",\"status\":\"CAPTURED\",\"amount\":{\"currency_code\":\"" + amount.currency
                        + "\",\"value\":\"" + amount.value + "\"}}],"
                : "";
        return json("{\"id\":\"" + order
                + "\",\"status\":\"COMPLETED\",\"purchase_units\":[{\"amount\":{\"currency_code\":\""
                + amount.currency + "\",\"value\":\"" + amount.value
                + "\"},\"payments\":{" + authorizations + "\"captures\":[{\"id\":\"" + capture
                + "\",\"status\":\"" + status + "\",\"amount\":{\"currency_code\":\""
                + amount.currency + "\",\"value\":\"" + amount.value + "\"}}]}}]}");
    }
    /** PayPal 의 기본 응답(Prefer: return=minimal): id·status·links 만 준다. 실제 Sandbox 에서 확인했다. */
    private static MockResponse minimal(String id, String status) {
        return json("{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"links\":[]}");
    }

    private static boolean wantsRepresentation(RecordedRequest request) {
        String prefer = request.getHeader("Prefer");
        return prefer != null && prefer.contains("return=representation");
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
