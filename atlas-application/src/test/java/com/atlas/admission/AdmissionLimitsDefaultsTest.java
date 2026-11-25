package com.atlas.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.admission.service.AdmissionLimits;
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
class AdmissionLimitsDefaultsTest {
    @Test
    void productionApplicationYmlMatchesCodeDefaults() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("production", new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(environment.getProperty("atlas.admission.default-max-active-checkouts", Integer.class))
                .isEqualTo(AdmissionLimits.DEFAULT_MAX_ACTIVE_CHECKOUTS);
        assertThat(environment.getProperty("atlas.admission.max-active-checkouts-limit", Integer.class))
                .isEqualTo(AdmissionLimits.MAX_ACTIVE_CHECKOUTS_LIMIT);
        assertThat(Duration.parse(environment.getProperty("atlas.admission.default-ticket-ttl")))
                .isEqualTo(Duration.parse(AdmissionLimits.DEFAULT_TICKET_TTL));
        assertThat(Duration.parse(environment.getProperty("atlas.admission.ticket-ttl-limit")))
                .isEqualTo(Duration.parse(AdmissionLimits.TICKET_TTL_LIMIT));
        assertThat(environment.getProperty("atlas.admission.max-live-tickets-per-buyer", Integer.class))
                .isEqualTo(AdmissionLimits.MAX_LIVE_TICKETS_PER_BUYER);
    }
}
