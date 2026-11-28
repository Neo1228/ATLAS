package internaltokenprobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.atlas.application.config.ApiSecurityConfiguration;
import com.atlas.application.security.AccountOwnershipInterceptor;
import com.atlas.application.security.InternalApiTokenAuthorization;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 주문-재고 API 토큰 검사를 실제 Tomcat 위에서 우회 경로로 두드린다.
 *
 * MockMvc 와 RestTemplate 은 요청 URI 를 다시 인코딩하거나 컨테이너의 경로 해석을 거치지 않으므로
 * {@code %69}, {@code ;} 같은 변형을 서버까지 그대로 보내려고 java.net.http.HttpClient 를 쓴다.
 * 패키지를 {@code com.atlas} 밖에 둔 것은 이 파일의 {@code @SpringBootConfiguration} 을 다른 슬라이스 테스트가
 * 설정 탐색 중에 집어 가지 않게 하려는 것이다.
 */
abstract class InternalApiTokenPathBypassTest {
    static final String TOKEN = "internal-api-token-path-bypass-test-32bytes";
    static final String REACHED = "internal-probe-reached";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @MockitoBean
    AccountOwnershipInterceptor ownership;

    @MockitoBean
    JwtDecoder jwtDecoder;

    abstract String contextPath();

    @BeforeEach
    void allowOwnershipInterceptor() throws Exception {
        when(ownership.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void canonicalPathWithTokenReachesController() throws Exception {
        HttpResponse<String> response = send("/api/internal/order-inventory/probe", TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(REACHED);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertThat(send("/api/internal/order-inventory/probe", TOKEN + "x").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/internal/order-inventory/probe",
            "/api/%69nternal/order-inventory/probe",
            "/%61pi/internal/order-inventory/probe",
            "/api/internal/order-inventory/%70robe",
            "/api/internal/order-inventory;x=1/probe",
            "/api;x=1/internal/order-inventory/probe",
            "/api/internal/order-inventory/probe;x=1",
            "//api/internal/order-inventory/probe",
            "/api//internal/order-inventory/probe",
            "/api/internal/order-inventory//probe",
            "/API/internal/order-inventory/probe",
            "/api/Internal/order-inventory/probe",
            "/api/internal/x/../order-inventory/probe",
            "/actuator/health/../../api/internal/order-inventory/probe",
            "/actuator/health;/../../api/internal/order-inventory/probe",
            "/actuator/health%2F..%2F..%2Fapi/internal/order-inventory/probe"
    })
    void pathVariantWithoutTokenNeverReachesController(String path) throws Exception {
        HttpResponse<String> response = send(path, null);
        assertThat(response.body()).as(path).doesNotContain(REACHED);
        assertThat(response.statusCode()).as(path).isGreaterThanOrEqualTo(400);
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        // URI.create 는 이미 인코딩된 문자열을 다시 인코딩하지 않는다.
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + contextPath() + path)).GET();
        if (token != null) {
            request.header("X-Atlas-Service-Token", token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootTest(classes = ProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
            "atlas.order-inventory-reservation.enabled=true",
            "atlas.order-inventory-reservation.api-token=" + TOKEN
    })
    static class RootContextPathTest extends InternalApiTokenPathBypassTest {
        @Override
        String contextPath() {
            return "";
        }
    }

    @SpringBootTest(classes = ProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
            "atlas.order-inventory-reservation.enabled=true",
            "atlas.order-inventory-reservation.api-token=" + TOKEN,
            "server.servlet.context-path=/atlas"
    })
    static class NonRootContextPathTest extends InternalApiTokenPathBypassTest {
        @Override
        String contextPath() {
            return "/atlas";
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, SecurityAutoConfiguration.class,
            SecurityFilterAutoConfiguration.class})
    @EnableWebSecurity
    @Import({ApiSecurityConfiguration.class, InternalApiTokenAuthorization.class, InternalProbe.class})
    static class ProbeApplication {
    }

    @RestController
    static class InternalProbe {
        @GetMapping("/api/internal/order-inventory/probe")
        String probe() {
            return REACHED;
        }
    }
}
