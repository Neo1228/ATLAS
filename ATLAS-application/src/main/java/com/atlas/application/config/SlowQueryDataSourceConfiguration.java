package com.atlas.application.config;

import com.atlas.application.filter.RequestCorrelationFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class SlowQueryDataSourceConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlowQueryDataSourceConfiguration.class);
    private static final String THRESHOLD_ENV = "ATLAS_DB_SLOW_QUERY_THRESHOLD_MS";

    @Bean
    static BeanPostProcessor slowQueryDataSourcePostProcessor(Environment environment) {
        String configuredThreshold = environment.getProperty(THRESHOLD_ENV);
        if (configuredThreshold == null || configuredThreshold.isBlank()) {
            LOGGER.info("slow_sql_detection enabled=false threshold_env={}", THRESHOLD_ENV);
            return new BeanPostProcessor() { };
        }

        long thresholdMs;
        try {
            thresholdMs = Long.parseLong(configuredThreshold);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(THRESHOLD_ENV + " must be a positive integer in milliseconds", exception);
        }
        if (thresholdMs <= 0) {
            throw new IllegalStateException(THRESHOLD_ENV + " must be greater than zero");
        }
        LOGGER.info("slow_sql_detection enabled=true threshold_ms={}", thresholdMs);

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || bean instanceof ProxyDataSource) {
                    return bean;
                }
                return ProxyDataSourceBuilder.create(dataSource)
                        .name(beanName)
                        .afterQuery((executionInfo, queryInfoList) -> {
                            if (executionInfo.getElapsedTime() < thresholdMs) {
                                return;
                            }
                            LOGGER.warn("slow_sql requestId={} datasource={} elapsed_ms={} success={} "
                                            + "statement_count={} statement_fingerprint={}",
                                    MDC.get(RequestCorrelationFilter.REQUEST_ID_MDC_KEY), beanName,
                                    executionInfo.getElapsedTime(), executionInfo.isSuccess(), queryInfoList.size(), fingerprint(queryInfoList));
                        })
                        .build();
            }
        };
    }

    private static String fingerprint(Iterable<QueryInfo> queryInfoList) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (QueryInfo queryInfo : queryInfoList) {
                digest.update(queryInfo.getQuery().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
