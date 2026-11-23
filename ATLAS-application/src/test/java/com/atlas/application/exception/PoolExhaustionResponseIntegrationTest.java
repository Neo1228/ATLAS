package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 연결 풀이 고갈된 상태의 실제 요청이 500이 아니라 Retry-After가 붙은 503으로 응답한다. */
@Testcontainers
@SpringBootTest
class PoolExhaustionResponseIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "300");
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.payment.recovery.worker.enabled", () -> "false");
        registry.add("atlas.paypal.webhook-retry.worker.enabled", () -> "false");
    }

    @Autowired DataSource dataSource;
    @Autowired WebApplicationContext context;

    @Test
    void exhaustedConnectionPoolAnswersServiceUnavailableWithRetryAfter() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        pool.setMinimumIdle(1);
        pool.setMaximumPoolSize(1);
        pool.getHikariPoolMXBean().softEvictConnections();
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        try (Connection held = dataSource.getConnection()) {
            // 유일한 연결을 쥔 채 요청하면 요청은 풀에서 연결을 얻지 못한다.
            assertThat(held.isValid(1)).isTrue();
            var result = mockMvc.perform(get("/api/products").param("limit", "5")).andReturn().getResponse();

            assertThat(result.getStatus()).isEqualTo(503);
            assertThat(result.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
            assertThat(result.getContentAsString()).contains("SERVICE_UNAVAILABLE");
        }
    }
}
