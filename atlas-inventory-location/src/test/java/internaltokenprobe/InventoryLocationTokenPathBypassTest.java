package internaltokenprobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.inventorylocation.security.InventoryLocationServiceTokenFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재고 location 서비스의 서비스 토큰 검사를 실제 Tomcat 위에서 우회 경로로 두드린다.
 *
 * MockMvc 와 RestTemplate 은 요청 URI 를 다시 인코딩하거나 컨테이너의 경로 해석을 거치지 않으므로
 * {@code %69}, {@code ;} 같은 변형을 서버까지 그대로 보내려고 java.net.http.HttpClient 를 쓴다.
 * 패키지를 {@code com.atlas} 밖에 둔 것은 이 파일의 {@code @SpringBootConfiguration} 을 다른 슬라이스 테스트가
 * 설정 탐색 중에 집어 가지 않게 하려는 것이다.
 */
abstract class InventoryLocationTokenPathBypassTest {
    static final String TOKEN = "internal-api-token-path-bypass-test-32bytes";
    static final String REACHED = "internal-probe-reached";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    abstract String contextPath();

    @Test
    void canonicalPathWithTokenReachesController() throws Exception {
        HttpResponse<String> response = send("/internal/v1/probe", TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(REACHED);
    }

    @Test
    void healthProbeStaysOpenWithoutToken() throws Exception {
        HttpResponse<String> response = send("/actuator/health", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("UP");
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        assertThat(send("/internal/v1/probe", TOKEN + "x").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/v1/probe",
            "/%69nternal/v1/probe",
            "/internal/%761/probe",
            "/internal/v1/%70robe",
            "/internal;x=1/v1/probe",
            "/internal/v1;x=1/probe",
            "/internal/v1/probe;x=1",
            "//internal/v1/probe",
            "/internal//v1/probe",
            "/internal/v1//probe",
            "/INTERNAL/v1/probe",
            "/Internal/v1/probe",
            "/internal/x/../v1/probe",
            "/other/probe",
            "/actuator/health/../../internal/v1/probe",
            "/actuator/health;/../../internal/v1/probe",
            "/actuator/health%2F..%2F..%2Finternal/v1/probe"
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
            "inventory.service-token=" + TOKEN
    })
    static class RootContextPathTest extends InventoryLocationTokenPathBypassTest {
        @Override
        String contextPath() {
            return "";
        }
    }

    @SpringBootTest(classes = ProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
            "inventory.service-token=" + TOKEN,
            "server.servlet.context-path=/atlas"
    })
    static class NonRootContextPathTest extends InventoryLocationTokenPathBypassTest {
        @Override
        String contextPath() {
            return "/atlas";
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class})
    @Import({InventoryLocationServiceTokenFilter.class, InternalProbe.class})
    static class ProbeApplication {
    }

    @RestController
    static class InternalProbe {
        @GetMapping({"/internal/v1/probe", "/other/probe"})
        String probe() {
            return REACHED;
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }
    }
}
