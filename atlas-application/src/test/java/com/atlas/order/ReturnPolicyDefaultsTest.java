package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.service.ReturnPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * 테스트 classpath의 {@code application.yml}이 운영 파일을 가리므로 운영 {@code application.yml}을 직접 읽어
 * 코드의 설정 없음 기본값과 같은지 확인한다. 두 값이 어긋나면 테스트가 보는 값과 운영이 쓰는 값이 달라진다.
 */
class ReturnPolicyDefaultsTest {
    @Test
    void productionApplicationYmlMatchesCodeDefaults() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("production", new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(Duration.parse(environment.getProperty("atlas.order.return.window")))
                .isEqualTo(Duration.parse(ReturnPolicy.DEFAULT_WINDOW));
        assertThat(environment.getProperty("atlas.order.return.window-enforced", Boolean.class))
                .isEqualTo(Boolean.parseBoolean(ReturnPolicy.DEFAULT_WINDOW_ENFORCED));
        assertThat(environment.getProperty("atlas.order.return.inspection-business-days", Integer.class))
                .isEqualTo(ReturnPolicy.DEFAULT_INSPECTION_BUSINESS_DAYS);
        assertThat(environment.getProperty("atlas.order.return.business-zone"))
                .isEqualTo(ReturnPolicy.DEFAULT_BUSINESS_ZONE);
    }
}
