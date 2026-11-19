package com.atlas.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 응답 압축이 켜져 있는지 고정합니다.
 *
 * <p>측정 배경: {@code /api/products?limit=100} 응답이 91 KiB 인데 압축 없이 전송됐습니다.
 * gzip-6 기준 6,567 bytes(7.2%)까지 줄어드는 크기였고, 서버 지연 264 ms 중
 * 직렬화·전송이 큰 부분이었습니다. 압축을 켜면 앱이 소켓에 쓰는 바이트가 1/14 이 됩니다.
 *
 * <p>애플리케이션 컨텍스트를 띄우지 않고 {@code application.yml} 을 직접 읽습니다.
 * 이 값들은 컨텍스트 없이도 검증할 수 있고, 그 편이 실패 원인을 즉시 알려줍니다.
 * MockMvc 는 실제 gzip 인코딩을 수행하지 않으므로 설정 계약만 고정합니다.
 * 실제 와이어 압축은 로컬 실행에서 확인했습니다(91,255 → 6,567 bytes).
 */
class ResponseCompressionConfigurationTest {

    /**
     * 운영 설정 파일을 읽습니다.
     *
     * <p>클래스패스에서 읽으면 {@code src/test/resources/application.yml} 이 먼저 잡혀
     * 테스트용 축약 설정을 검사하게 됩니다. 그래서 소스 경로를 직접 엽니다.
     * Gradle 테스트 작업의 작업 디렉터리는 모듈 루트입니다.
     */
    private static String applicationYaml() throws IOException {
        Path path = Path.of("src/main/resources/application.yml");
        if (!Files.exists(path)) {
            path = Path.of("ATLAS-application/src/main/resources/application.yml");
        }
        assertThat(Files.exists(path)).as("운영 application.yml 을 찾아야 한다: %s", path.toAbsolutePath()).isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void compressionIsEnabled() throws IOException {
        String yaml = applicationYaml();
        assertThat(yaml).contains("compression:");
        assertThat(yaml).containsPattern("(?s)compression:\\s*\\n(\\s+)enabled:\\s*true");
    }

    @Test
    void jsonIsInCompressedMimeTypes() throws IOException {
        assertThat(applicationYaml()).contains("application/json");
    }

    @Test
    void smallResponsesAreNotCompressed() throws IOException {
        // 작은 응답은 압축 이득 없이 CPU 와 지연만 늘어난다.
        assertThat(applicationYaml()).contains("min-response-size: 2KB");
    }

}
