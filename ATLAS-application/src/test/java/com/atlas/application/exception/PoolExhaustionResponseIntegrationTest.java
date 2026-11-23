package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
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

        try (Connection held = awaitSoleConnection(pool)) {
            // 유일한 연결을 쥔 채 요청하면 요청은 풀에서 연결을 얻지 못한다.
            assertThat(held.isValid(1)).isTrue();
            var result = mockMvc.perform(get("/api/products").param("limit", "5")).andReturn().getResponse();

            assertThat(result.getStatus()).isEqualTo(503);
            assertThat(result.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
            assertThat(result.getContentAsString()).contains("SERVICE_UNAVAILABLE");
        }
    }

    /**
     * 풀을 1개로 줄인 직후에는 이전 연결이 아직 사용 중이거나 정리 중일 수 있다. 그 상태로 요청하면
     * 연결 획득이 시간 초과로 실패하거나, 남은 유휴 연결이 요청을 처리해 고갈 상황이 되지 않는다.
     * 연결을 얻고 풀에 그 연결만 남을 때까지 기다려 두 경우를 모두 배제한다.
     */
    private static Connection awaitSoleConnection(HikariDataSource pool) throws Exception {
        HikariPoolMXBean metrics = pool.getHikariPoolMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        Connection held = null;
        while (System.nanoTime() < deadline) {
            if (held == null) {
                try {
                    held = pool.getConnection();
                } catch (SQLException unavailable) {
                    Thread.sleep(50);
                    continue;
                }
            }
            if (metrics.getTotalConnections() == 1) return held;
            Thread.sleep(50);
        }
        if (held != null) held.close();
        throw new IllegalStateException("연결 풀이 15초 안에 연결 1개로 정리되지 않았다");
    }
}
