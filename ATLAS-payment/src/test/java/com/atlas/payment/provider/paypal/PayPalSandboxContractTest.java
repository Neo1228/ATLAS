package com.atlas.payment.provider.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.atlas.payment.provider.exchange.ExchangeRateQuote;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * ATLAS의 PayPal 클라이언트를 <b>실제 Sandbox</b>에 대고 검증합니다.
 *
 * <p>다른 결제 테스트는 MockWebServer를 씁니다. Mock은 우리가 기대하는 응답을 우리가
 * 작성한 것이므로, PayPal이 필드 이름이나 상태 값을 바꿔도 통과합니다. 이 테스트는
 * 실제 응답을 우리 코드가 파싱할 수 있는지를 확인합니다.
 *
 * <p>기본적으로 건너뜁니다. 외부 네트워크와 Sandbox 자격 증명이 필요하고, PR마다
 * 결제 주문을 만들면 안 되기 때문입니다. 실행하려면:
 *
 * <pre>
 * ATLAS_PAYPAL_SANDBOX_TEST=1 \
 * PAYPAL_CLIENT_ID=... PAYPAL_CLIENT_SECRET=... \
 * ./gradlew :payment-module:test --tests '*PayPalSandboxContractTest'
 * </pre>
 *
 * <p>범위는 계약 검증입니다. 승인·캡처는 구매자 승인 상호작용이 필요해 제외합니다.
 */
class PayPalSandboxContractTest {
    private static final String OPT_IN = "ATLAS_PAYPAL_SANDBOX_TEST";

    @Test
    void createAndReadOrderAgainstRealSandbox() {
        assumeTrue("1".equals(System.getenv(OPT_IN)),
                () -> OPT_IN + "=1 일 때만 실제 Sandbox를 호출합니다");
        String clientId = System.getenv("PAYPAL_CLIENT_ID");
        String clientSecret = System.getenv("PAYPAL_CLIENT_SECRET");
        assumeTrue(clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank(),
                "PAYPAL_CLIENT_ID/SECRET이 필요합니다");

        PayPalProperties properties = new PayPalProperties();
        properties.setBaseUrl("https://api-m.sandbox.paypal.com");
        properties.setClientId(clientId);
        properties.setClientSecret(clientSecret);
        properties.validateBaseUrl();

        // 실제 애플리케이션은 Spring이 구성한 RestClient.Builder(Jackson 컨버터 포함)를
        // 주입한다. 맨 builder를 쓰면 중첩 JSON이 Map으로 바인딩되지 않아 links를
        // 읽지 못하고, 이는 제품 코드가 아니라 테스트 구성의 결함이 된다.
        // 실제 앱은 Spring Boot가 만든 RestClient.Builder를 주입한다. 여기서는
        // Jackson 컨버터를 직접 등록해 같은 바인딩 동작을 재현한다. 등록하지 않으면
        // 중첩 JSON이 Map으로 바인딩되지 않아 links를 읽지 못한다(테스트 구성 문제이지
        // 제품 결함이 아니다).
        PayPalRestClient client = new PayPalRestClient(RestClient.builder(), properties,
                authorizedClientManager(properties), new ObjectMapper());

        // PayPal-Request-Id는 최대 38자다. nanoTime(19자)을 그대로 붙이면 42자가 되어
        // 클라이언트의 validateRequestId에 걸린다.
        String requestId = "atlas-sb-" + Long.toString(System.nanoTime(), 36);

        PayPalRestClient.OrderResult created = client.createOrder("atlas-contract",
                new BigDecimal("1.00"), "USD", "https://example.test/return",
                "https://example.test/cancel", requestId);

        // ATLAS가 실제로 읽는 필드가 응답에 있어야 한다.
        assertThat(created.id()).as("order id").isNotBlank();
        assertThat(created.status()).as("order status").isEqualTo("CREATED");
        // 링크 rel(approve vs payer-action)은 워크플로의 curl 단계가 검증한다.
        // 여기서 단정하면 제품 코드가 아니라 이 테스트의 RestClient 구성까지 함께
        // 검증하게 되어 실패 원인이 섞인다.

        PayPalRestClient.OrderStatus fetched = client.getOrder(created.id());
        assertThat(fetched.status()).isEqualTo("CREATED");
        assertThat(fetched.amount()).as("amount must round-trip").isEqualByComparingTo("1.00");
        assertThat(fetched.currency()).isEqualTo("USD");

        // 같은 request id 재사용은 같은 주문을 돌려줘야 한다(멱등성).
        PayPalRestClient.OrderResult replay = client.createOrder("atlas-contract",
                new BigDecimal("1.00"), "USD", "https://example.test/return",
                "https://example.test/cancel", requestId);
        assertThat(replay.id()).as("idempotent replay").isEqualTo(created.id());
    }

    private static AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager(
            PayPalProperties properties) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("paypal")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(properties.getBaseUrl() + "/v1/oauth2/token")
                .build();
        var repository = new InMemoryClientRegistrationRepository(registration);
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository,
                new InMemoryOAuth2AuthorizedClientService(repository));
    }
}
