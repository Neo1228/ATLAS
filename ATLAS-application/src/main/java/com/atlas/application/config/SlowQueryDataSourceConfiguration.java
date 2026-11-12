package com.atlas.application.config;

import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                            String statements = queryInfoList.stream()
                                    .map(QueryInfo::getQuery)
                                    .collect(Collectors.joining(" | "));
                            LOGGER.warn("slow_sql datasource={} elapsed_ms={} success={} statement={}",
                                    beanName, executionInfo.getElapsedTime(), executionInfo.isSuccess(), statements);
                        })
                        .build();
            }
        };
    }
}
