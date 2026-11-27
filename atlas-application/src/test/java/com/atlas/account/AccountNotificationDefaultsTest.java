package com.atlas.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * 테스트 classpath의 {@code application.yml}이 운영 파일을 가리므로 운영 파일을 직접 읽어 인증 메일 outbox가 기본으로
 * 켜져 있는지 확인한다(ADR-0102, EXP-113: 꺼진 경로는 커밋 직후 종료·SMTP 오류에서 메일을 잃는다).
 */
class AccountNotificationDefaultsTest {
    @Test
    void productionApplicationYmlEnablesTheNotificationOutboxByDefault() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("production", new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(environment.getProperty("atlas.account.notification.outbox.enabled", Boolean.class)).isTrue();
    }
}
