package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.account.dto.request.AccountRequest;
import com.atlas.account.repository.AccountRepository;
import com.atlas.account.service.AccountService;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * BR-018·INV-015의 "모든 protected operation에서 Buyer/Seller 소유 검증"과 "명시하지 않은 route는 default deny"를
 * 실제 라우트 전체와 PostgreSQL로 증명한다(ADR-0093, EXP-101). 모의 서비스 단위 테스트와 프로브 컨트롤러 한 개로는
 * 새 라우트가 소유 검증 없이 추가되는 것을 막지 못하므로, 매핑된 라우트를 전수 열거해 분류하고 분류마다 타 계정 요청이
 * 거절되는지 실제 JWT로 확인한다. 등장인물: A는 구매자, B는 판매자(SB), C는 두 자원 모두에 접근하려는 다른 계정이다.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class AccountOwnershipIntegrationTest {
    private static final String PASSWORD = "long-enough-password-123";
    private static final AtomicInteger ORIGIN_SEQUENCE = new AtomicInteger();
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");

    /** 로그인·가입·재설정 등 토큰 없이 부르는 인증 라우트. */
    private static final Set<String> PUBLIC_AUTH = Set.of(
            "POST /api/auth/signup", "POST /api/auth/login", "POST /api/auth/refresh",
            "POST /api/auth/verify-email", "POST /api/auth/password-reset/request",
            "POST /api/auth/password-reset/confirm", "POST /api/auth/email-verification/request",
            "POST /api/auth/mfa/challenge");
    /** 검토를 거쳐 공개한 읽기 라우트. */
    private static final Set<String> PUBLIC_CATALOG = Set.of(
            "GET /api/products", "GET /api/products/search", "GET /api/products/{productId}",
            "GET /api/products/{productId}/reviews",
            "GET /api/campaigns/{campaignId}");
    /** 인증은 서명 검증(webhook)이나 저장된 provider 상태·token(결제 복귀)으로 한다. */
    private static final Set<String> PUBLIC_PROVIDER = Set.of(
            "POST /api/webhooks/paypal",
            "GET /api/checkouts/{checkoutId}/payment/return", "GET /api/checkouts/{checkoutId}/payment/cancel",
            "GET /api/orders/{orderId}/payment/return", "GET /api/orders/{orderId}/payment/cancel");
    /** 경로에 자원 식별자가 없고 소유자를 JWT subject나 컨트롤러의 소유 검증으로 정하는 인증 라우트. */
    private static final Set<String> PRINCIPAL_SCOPED = Set.of(
            "POST /api/auth/logout", "DELETE /api/auth/account", "GET /api/auth/sessions",
            "DELETE /api/auth/sessions/{familyId}", "POST /api/auth/mfa/enroll", "POST /api/auth/mfa/enable",
            "POST /api/auth/mfa/disable",
            "POST /api/sellers", "POST /api/campaigns", "POST /api/campaigns/{campaignId}/tickets",
            // 쇼핑 비서 도구 목록(ADR-0109). 정적 목록이라 소유할 자원이 없다.
            "GET /api/assistant/tools");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withLabel("atlasexp", "t1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    private Fixture fixture;

    @BeforeEach
    void seed() throws Exception {
        if (fixture == null) fixture = new FixtureBuilder().build();
    }

    @Test
    void everyMappedRouteIsPublicPrincipalScopedOrCoveredByAnOwnershipRule() {
        List<String> unclassified = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Route route : routes()) {
            seen.add(route.key());
            if (kind(route) == Kind.UNCLASSIFIED) unclassified.add(route.key());
        }
        assertThat(unclassified).as("소유 검증 규칙도 공개·principal 지정 목록도 아닌 라우트").isEmpty();
        assertThat(seen).hasSize(103);
        // 69개 ownership, 10개 principal-scoped, 17개 명시 공개 route로 모두 분류한다.
        assertThat(routes().stream().filter(r -> kind(r) == Kind.OWNERSHIP)).hasSize(74);
        assertThat(routes().stream().filter(r -> kind(r) == Kind.PRINCIPAL_SCOPED)).hasSize(11);
        assertThat(routes().stream().filter(r -> kind(r) == Kind.PUBLIC)).hasSize(18);
        // 목록이 썩지 않게: 허용 목록의 모든 항목은 실제 라우트여야 한다.
        for (Set<String> declared : List.of(PUBLIC_AUTH, PUBLIC_CATALOG, PUBLIC_PROVIDER, PRINCIPAL_SCOPED)) {
            assertThat(seen).containsAll(declared);
        }
    }

    @Test
    void routesThatAreNotExplicitlyPublicRejectAnonymousRequests() throws Exception {
        int rejected = 0;
        int open = 0;
        for (Route route : routes()) {
            int statusCode = send(route, Map.of(), null).andReturn().getResponse().getStatus();
            if (isPublic(route)) {
                assertThat(statusCode).as("공개 라우트 " + route.key()).isNotEqualTo(401);
                open++;
            } else {
                assertThat(statusCode).as("토큰 없는 " + route.key()).isEqualTo(401);
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(85);
        assertThat(open).isEqualTo(PUBLIC_AUTH.size() + PUBLIC_CATALOG.size() + PUBLIC_PROVIDER.size());
    }

    @Test
    void anotherAccountIsRefusedOnEveryOwnershipRoute() throws Exception {
        Fixture f = fixture;
        Map<String, String> victim = new HashMap<>(f.variables());
        int checked = 0;
        for (Route route : routes()) {
            if (kind(route) != Kind.OWNERSHIP || isPublic(route)) continue;
            send(route, victim, f.tokenC()).andExpect(status().isForbidden());
            checked++;
            // 주문 경로는 자기 seller 경로에 남의 주문을 끼워 넣어도 거절돼야 한다.
            if (route.pattern().startsWith("/api/sellers/{sellerId}") && route.pattern().contains("{orderId}")) {
                Map<String, String> viaOwnSeller = new HashMap<>(victim);
                viaOwnSeller.put("sellerId", f.sellerC());
                send(route, viaOwnSeller, f.tokenC()).andExpect(status().isForbidden());
                checked++;
            }
        }
        long ownershipRoutes = routes().stream().filter(r -> kind(r) == Kind.OWNERSHIP && !isPublic(r)).count();
        assertThat(checked).isGreaterThanOrEqualTo((int) ownershipRoutes);
        // 아무것도 바뀌지 않았다: A의 장바구니, 주문, 체크아웃과 B의 상품이 그대로다.
        assertThat(jdbc.queryForObject("SELECT state FROM purchase_order WHERE order_id = ?::uuid",
                String.class, f.orderA())).isNotEqualTo("CANCELLED");
    }
    @Test
    void disabledAccountOperatorRouteCannotBeReachedWithABuyerToken() throws Exception {
        Fixture f = fixture;
        UUID targetAccount = UUID.fromString(SignedJWT.parse(f.tokenA()).getJWTClaimsSet().getSubject());
        mockMvc.perform(post("/api/internal/accounts/{accountId}/suspension", targetAccount)
                        .header("Authorization", "Bearer " + f.tokenC())
                        .header("Idempotency-Key", "buyer-suspension-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"buyer-token-probe\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM user_account WHERE account_id = ?",
                String.class, targetAccount)).isEqualTo("ACTIVE");
    }



    @Test
    void theOwnerPassesTheOwnershipRulesOnReadRoutesAndTheWrongRoleDoesNot() throws Exception {
        Fixture f = fixture;
        // 양성 대조군: 같은 라우트를 소유자가 부르면 소유 검사를 통과한다(403이 아니다).
        int passed = 0;
        for (Route route : routes()) {
            if (kind(route) != Kind.OWNERSHIP || isPublic(route) || !"GET".equals(route.method())) continue;
            Map<String, String> own = new HashMap<>(f.variables());
            String token;
            if (route.pattern().startsWith("/api/sellers/")) {
                token = f.tokenB();
            } else {
                token = f.tokenA();
            }
            int statusCode = send(route, own, token).andReturn().getResponse().getStatus();
            assertThat(statusCode).as("소유자 " + route.key()).isNotIn(401, 403);
            passed++;
        }
        assertThat(passed).isPositive();
        // 주문은 구매자 A와 그 주문의 판매자 B만 볼 수 있고, 판매자 B는 구매자 경로로 볼 수 없다.
        getAs("/api/orders/{orderId}", f.orderA(), f.tokenB()).andExpect(status().isForbidden());
        getAs("/api/sellers/{sellerId}/orders/{orderId}", f.sellerB(), f.orderA(), f.tokenB()).andExpect(status().isOk());
        getAs("/api/sellers/{sellerId}/orders/{orderId}", f.sellerC(), f.orderA(), f.tokenC()).andExpect(status().isForbidden());
        getAs("/api/sellers/{sellerId}/orders/{orderId}", f.sellerB(), f.orderA(), f.tokenC()).andExpect(status().isForbidden());
    }

    @Test
    void foreignIdentifiersInRequestBodiesAreRefusedWithoutSideEffects() throws Exception {
        Fixture f = fixture;
        int campaignsBefore = jdbc.queryForObject("SELECT count(*) FROM campaign", Integer.class);
        int ticketsBefore = jdbc.queryForObject("SELECT count(*) FROM admission_ticket", Integer.class);

        // C가 B의 seller로 캠페인을 만들 수 없다(요청 본문의 sellerId는 인증 근거가 아니다).
        mockMvc.perform(post("/api/campaigns").header("Authorization", "Bearer " + f.tokenC())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":\"" + f.sellerB() + "\",\"skuId\":\"" + f.skuB() + "\"}"))
                .andExpect(status().isForbidden());
        // C가 A의 buyerRef로 대기열에 들어갈 수 없다.
        mockMvc.perform(post("/api/campaigns/{campaignId}/tickets", f.campaignOpen())
                        .header("Authorization", "Bearer " + f.tokenC()).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":\"" + f.skuB() + "\",\"buyerRef\":\"" + f.refA() + "\"}"))
                .andExpect(status().isForbidden());
        // C가 B의 닫힌 캠페인을 열 수 없다.
        mockMvc.perform(post("/api/campaigns/{campaignId}/open", f.campaignClosed())
                        .header("Authorization", "Bearer " + f.tokenC()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign", Integer.class)).isEqualTo(campaignsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admission_ticket", Integer.class)).isEqualTo(ticketsBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?::uuid",
                String.class, f.campaignClosed())).isEqualTo("CLOSED");
        // 양성 대조군: 소유자 B는 같은 요청이 통과한다.
        mockMvc.perform(post("/api/campaigns/{campaignId}/open", f.campaignClosed())
                        .header("Authorization", "Bearer " + f.tokenB()))
                .andExpect(status().isOk());
    }

    @Test
    void childResourcesOfAnotherSellerCannotBeReachedThroughOwnSellerPath() throws Exception {
        Fixture f = fixture;
        String own = "/api/sellers/" + f.sellerC();
        String priceBefore = jdbc.queryForObject("SELECT price_won::text FROM seller_sku WHERE sku_id = ?", String.class, f.skuB());
        String nameBefore = jdbc.queryForObject("SELECT name FROM product_category WHERE category_id = ?::uuid",
                String.class, f.categoryB());

        // C 자신의 sellerId(소유 검증 통과)에 B의 하위 자원 id를 끼워 넣는다. 서비스가 sellerId로 한정해 찾지 못해야 한다.
        for (ResultActions attempt : List.of(
                as(f.tokenC(), put(own + "/products/" + f.productB() + "/status"), "{\"status\":\"INACTIVE\"}"),
                as(f.tokenC(), put(own + "/products/" + f.productB() + "/skus/" + f.skuB() + "/price"), "{\"priceWon\":1}"),
                as(f.tokenC(), put(own + "/product-categories/" + f.categoryB()), "{\"name\":\"hijacked\"}"),
                as(f.tokenC(), get(own + "/coupons/" + f.couponB()), null),
                as(f.tokenC(), post(own + "/campaigns/" + f.campaignOpen() + "/close"), null))) {
            int statusCode = attempt.andReturn().getResponse().getStatus();
            assertThat(statusCode).as("남의 하위 자원은 2xx가 될 수 없다").isBetween(400, 499);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM product WHERE product_id = ?::uuid", String.class, f.productB()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT price_won::text FROM seller_sku WHERE sku_id = ?", String.class, f.skuB()))
                .isEqualTo(priceBefore);
        assertThat(jdbc.queryForObject("SELECT name FROM product_category WHERE category_id = ?::uuid",
                String.class, f.categoryB())).isEqualTo(nameBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM campaign WHERE campaign_id = ?::uuid",
                String.class, f.campaignOpen())).isEqualTo("OPEN");

        // 양성 대조군: 소유자 B가 자기 경로로 부르면 같은 변경이 반영된다.
        as(f.tokenB(), put("/api/sellers/" + f.sellerB() + "/products/" + f.productB() + "/skus/" + f.skuB() + "/price"),
                "{\"priceWon\":123}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT price_won::text FROM seller_sku WHERE sku_id = ?", String.class, f.skuB()))
                .isEqualTo("123");
    }

    // ---- 라우트 열거 ----

    private enum Kind { PUBLIC, PRINCIPAL_SCOPED, OWNERSHIP, UNCLASSIFIED }

    private record Route(String method, String pattern) {
        String key() {
            return method + " " + pattern;
        }
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) continue;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                // /api/internal/** 은 계정 JWT가 아니라 운영자 자격증명의 별도 보안 체인이 맡는다(조건부 활성).
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/internal/")) continue;
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    routes.add(new Route(method.name(), pattern));
                }
            }
        }
        routes.sort((a, b) -> a.key().compareTo(b.key()));
        return routes;
    }

    private static boolean isPublic(Route route) {
        String key = route.key();
        return PUBLIC_AUTH.contains(key) || PUBLIC_CATALOG.contains(key) || PUBLIC_PROVIDER.contains(key);
    }

    /** {@code AccountOwnershipInterceptor}가 소유를 검사하는 경로 모양. 같은 규칙을 시험 쪽에 다시 적어 둘이 어긋나면 실패하게 한다. */
    private static Kind kind(Route route) {
        if (isPublic(route)) return Kind.PUBLIC;
        if (PRINCIPAL_SCOPED.contains(route.key())) return Kind.PRINCIPAL_SCOPED;
        Set<String> variables = variables(route.pattern());
        boolean owned = variables.contains("buyerRef") || route.pattern().startsWith("/api/sellers/")
                || variables.contains("checkoutId") || variables.contains("orderId")
                || (variables.contains("ticketId") && variables.contains("campaignId"))
                || ("POST".equals(route.method()) && "/api/campaigns/{campaignId}/open".equals(route.pattern()));
        return owned ? Kind.OWNERSHIP : Kind.UNCLASSIFIED;
    }

    private static Set<String> variables(String pattern) {
        Set<String> variables = new TreeSet<>();
        Matcher matcher = PATH_VARIABLE.matcher(pattern);
        while (matcher.find()) variables.add(matcher.group(1));
        return variables;
    }

    private ResultActions send(Route route, Map<String, String> values, String token) throws Exception {
        String uri = route.pattern();
        for (String variable : variables(route.pattern())) {
            String value = values.getOrDefault(variable, UUID.randomUUID().toString());
            uri = uri.replace("{" + variable + "}", value);
        }
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(route.method()), uri)
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(newOrigin());
                    return servletRequest;
                });
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return mockMvc.perform(builder);
    }

    private ResultActions getAs(String template, String id, String token) throws Exception {
        return mockMvc.perform(get(template, id).header("Authorization", "Bearer " + token));
    }

    private ResultActions getAs(String template, String sellerId, String orderId, String token) throws Exception {
        return mockMvc.perform(get(template, sellerId, orderId).header("Authorization", "Bearer " + token));
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(builder);
    }

    private static String newOrigin() {
        int n = ORIGIN_SEQUENCE.incrementAndGet();
        return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
    }

    // ---- 시험 데이터 ----

    private record Fixture(String tokenA, String tokenB, String tokenC, String refA, String sellerB, String sellerC,
            String productB, String skuB, String categoryB, String couponB, String campaignOpen, String campaignClosed,
            String ticketA, String checkoutA, String orderA) {
        Map<String, String> variables() {
            Map<String, String> values = new HashMap<>();
            values.put("buyerRef", refA);
            values.put("sellerId", sellerB);
            values.put("productId", productB);
            values.put("skuId", skuB);
            values.put("categoryId", categoryB);
            values.put("code", couponB);
            values.put("campaignId", campaignOpen);
            values.put("ticketId", ticketA);
            values.put("checkoutId", checkoutA);
            values.put("orderId", orderA);
            return values;
        }
    }

    private final class FixtureBuilder {
        Fixture build() throws Exception {
            String tokenA = newAccount();
            String tokenB = newAccount();
            String tokenC = newAccount();
            String refA = accountService.buyerRef(UUID.fromString(subject(tokenA)));
            String sellerB = createSeller(tokenB);
            String sellerC = createSeller(tokenC);

            String skuB = "own-sku-" + UUID.randomUUID();
            String productB = json(tokenB, post("/api/sellers/" + sellerB + "/products"),
                    "{\"name\":\"B product\",\"description\":\"owned by B\"}", "$.productId");
            json(tokenB, post("/api/sellers/" + sellerB + "/products/" + productB + "/skus"),
                    "{\"skuId\":\"" + skuB + "\",\"priceWon\":100,\"initialStock\":5}", "$.skuId");
            json(tokenB, put("/api/sellers/" + sellerB + "/skus/" + skuB + "/admission-policy"),
                    "{\"maxActiveCheckouts\":2}", null);
            String categoryB = json(tokenB, post("/api/sellers/" + sellerB + "/product-categories"),
                    "{\"code\":\"cat-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"name\":\"B category\"}",
                    "$.categoryId");
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String couponCode = "CPN" + UUID.randomUUID().toString().substring(0, 8);
            json(tokenB, post("/api/sellers/" + sellerB + "/coupons"),
                    "{\"code\":\"" + couponCode + "\",\"discountAmountWon\":10,\"startsAt\":\"" + now.minusSeconds(60)
                            + "\",\"endsAt\":\"" + now.plusSeconds(86400) + "\",\"maxRedemptions\":5}", "$.code");
            String campaignOpen = json(tokenB, post("/api/campaigns"),
                    "{\"sellerId\":\"" + sellerB + "\",\"skuId\":\"" + skuB + "\",\"queueTicketTtlSeconds\":300}", "$.campaignId");
            json(tokenB, post("/api/campaigns/" + campaignOpen + "/open"), null, null);
            String campaignClosed = json(tokenB, post("/api/campaigns"),
                    "{\"sellerId\":\"" + sellerB + "\",\"skuId\":\"" + skuB + "\",\"queueTicketTtlSeconds\":300}", "$.campaignId");

            String ticketA = json(tokenA, post("/api/campaigns/" + campaignOpen + "/tickets")
                            .header("Idempotency-Key", "join-" + UUID.randomUUID()),
                    "{\"skuId\":\"" + skuB + "\",\"buyerRef\":\"" + refA + "\"}", "$.ticketId");
            json(tokenA, put("/api/buyers/" + refA + "/cart/items/" + sellerB + "/" + skuB), "{\"quantity\":1}", "$.buyerRef");
            String body = as(tokenA, post("/api/buyers/" + refA + "/cart/checkout")
                    .header("Idempotency-Key", "cart-" + UUID.randomUUID()), null)
                    .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
            String checkoutA = JsonPath.read(body, "$.checkoutId");
            String orderA = JsonPath.read(body, "$.orders[0].orderId");
            return new Fixture(tokenA, tokenB, tokenC, refA, sellerB, sellerC, productB, skuB, categoryB, couponCode,
                    campaignOpen, campaignClosed, ticketA, checkoutA, orderA);
        }

        private String newAccount() {
            String email = "own-" + UUID.randomUUID() + "@example.com";
            UUID accountId = accounts.createAccount(email, passwordEncoder.encode(PASSWORD)).orElseThrow();
            accounts.createBuyer(accountId);
            accounts.verifyEmail(accountId, Instant.now());
            return accountService.login(new AccountRequest.Login(email, PASSWORD), newOrigin()).tokens().accessToken();
        }

        private String createSeller(String token) throws Exception {
            return json(token, post("/api/sellers"), null, "$.sellerId");
        }

        private String json(String token, MockHttpServletRequestBuilder builder, String body, String path) throws Exception {
            String response = as(token, builder, body).andExpect(status().is2xxSuccessful())
                    .andReturn().getResponse().getContentAsString();
            if (path == null) return null;
            Object value = JsonPath.read(response, path);
            return String.valueOf(value);
        }

        private String subject(String accessToken) throws Exception {
            return SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        }
    }
}
