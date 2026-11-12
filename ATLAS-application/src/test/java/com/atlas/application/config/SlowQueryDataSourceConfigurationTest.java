package com.atlas.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.atlas.payment.PayPalIntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
@Testcontainers
@SpringBootTest(properties = "ATLAS_DB_SLOW_QUERY_THRESHOLD_MS=200")
class SlowQueryDataSourceConfigurationTest extends PayPalIntegrationTestSupport {

    private static final String THRESHOLD_ENV = "ATLAS_DB_SLOW_QUERY_THRESHOLD_MS";
    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(SlowQueryDataSourceConfiguration.class);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    @Autowired private DataSource applicationDataSource;
    @Autowired private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Test
    void applicationDataSourcePreservesHikariMetricsWhenSlowQueryDetectionIsEnabled() throws Exception {
        assertThat(applicationDataSource).isInstanceOf(ProxyDataSource.class);
        assertThat(applicationDataSource.unwrap(HikariDataSource.class)).isNotNull();
        try (Connection connection = applicationDataSource.getConnection()) {
            connection.createStatement().execute("SELECT 1");
        }
        assertThat(meterRegistry.find("hikaricp.connections.active").meter()).isNotNull();
    }

    @Test
    void logsSlowPreparedStatementWithoutBindValuesAndKeepsHikariUnwrapping() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        LOGGER.addAppender(appender);

        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikari.setUsername(POSTGRES.getUsername());
        hikari.setPassword(POSTGRES.getPassword());
        try {
            BeanPostProcessor postProcessor = SlowQueryDataSourceConfiguration.slowQueryDataSourcePostProcessor(
                    new MockEnvironment().withProperty(THRESHOLD_ENV, "200"));
            DataSource dataSource = (DataSource) postProcessor.postProcessAfterInitialization(hikari, "dataSource");

            assertThat(dataSource.unwrap(HikariDataSource.class)).isSameAs(hikari);
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("SELECT 1");
                try (PreparedStatement statement = connection.prepareStatement("SELECT pg_sleep(?)")) {
                    statement.setDouble(1, 0.4);
                    statement.execute();
                }
            }

            List<String> slowMessages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("slow_sql"))
                    .toList();
            assertThat(slowMessages).hasSize(1);
            assertThat(slowMessages.get(0))
                    .contains("datasource=dataSource", "elapsed_ms=", "success=true", "SELECT pg_sleep(?)")
                    .doesNotContain("0.4");
        } finally {
            hikari.close();
            LOGGER.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void leavesDataSourceUnwrappedWhenThresholdIsNotConfigured() {
        HikariDataSource hikari = new HikariDataSource();
        try {
            BeanPostProcessor postProcessor = SlowQueryDataSourceConfiguration.slowQueryDataSourcePostProcessor(
                    new MockEnvironment());
            assertThat(postProcessor.postProcessAfterInitialization(hikari, "dataSource")).isSameAs(hikari);
        } finally {
            hikari.close();
        }
    }

    @Test
    void rejectsNonPositiveOrMalformedThreshold() {
        assertThatThrownBy(() -> SlowQueryDataSourceConfiguration.slowQueryDataSourcePostProcessor(
                new MockEnvironment().withProperty(THRESHOLD_ENV, "0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> SlowQueryDataSourceConfiguration.slowQueryDataSourcePostProcessor(
                new MockEnvironment().withProperty(THRESHOLD_ENV, "fast")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive integer");
    }
}
