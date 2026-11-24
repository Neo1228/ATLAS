package com.atlas.application.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 입장 게이트의 Redis 연결을 빈으로 만들어 컨테이너가 생성과 종료를 소유하게 합니다. */
@Configuration
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.admission-gate.enabled", havingValue = "true")
public class RedisStockAdmissionControlConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient admissionGateRedisClient(
            @Value("${atlas.order-inventory-reservation.admission-gate.redis-uri}") String redisUri,
            @Value("${atlas.order-inventory-reservation.admission-gate.command-timeout:PT0.2S}") Duration commandTimeout) {
        RedisURI uri = RedisURI.create(redisUri);
        uri.setTimeout(commandTimeout);
        return RedisClient.create(uri);
    }
}
