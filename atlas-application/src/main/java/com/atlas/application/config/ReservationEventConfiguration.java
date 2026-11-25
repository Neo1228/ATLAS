package com.atlas.application.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 예약 이벤트 로그를 Kafka로 내보내는 relay의 producer와 전용 scheduler.
 *
 * <p>relay를 켠 경우에만 만들어지고, Boot의 Kafka 자동구성에 기대지 않는다(전역 consumer/admin 설정을 끌어오지 않는다).
 * producer는 acks=all, idempotence=true라서 retry가 있어도 같은 파티션 안의 순서와 중복 방지가 유지된다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.event-relay.enabled", havingValue = "true")
public class ReservationEventConfiguration {
    @Bean
    public DefaultKafkaProducerFactory<String, String> reservationEventProducerFactory(
            @Value("${atlas.order-inventory-reservation.event-relay.bootstrap-servers:localhost:9092}")
            String bootstrapServers,
            @Value("${atlas.order-inventory-reservation.event-relay.send-timeout:PT10S}") Duration sendTimeout) {
        if (bootstrapServers.isBlank()) throw new IllegalArgumentException("Invalid event relay bootstrap servers");
        if (sendTimeout.compareTo(Duration.ofSeconds(1)) < 0 || sendTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid event relay send timeout");
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // send-timeout이 한 묶음의 전달 한도다. 한도 안에 못 보내면 future가 실패하고 relay는 cursor를 옮기지 않는다.
        int timeoutMillis = Math.toIntExact(sendTimeout.toMillis());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutMillis);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis / 2);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMillis);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> reservationEventKafkaTemplate(
            DefaultKafkaProducerFactory<String, String> reservationEventProducerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(reservationEventProducerFactory);
        // 기본 LoggingProducerListener는 전송에 실패한 레코드마다 payload를 ERROR로 남긴다. broker가 멈추면 한 묶음이
        // 수백 줄이 되어 relay가 1분에 한 번만 남기는 실패 로그(ReservationEventWorker) 설계를 우회하므로 끈다.
        // 실패는 publisher가 던지는 예외와 relay의 실패 counter·throttle된 WARN으로 드러난다.
        template.setProducerListener(new ProducerListener<>() {
        });
        return template;
    }

    /** Kafka 전송 대기가 다른 복구 worker의 scheduler 자리를 차지하지 않도록 전용 thread 하나를 둔다. */
    @Bean(name = "reservationEventTaskScheduler")
    public ThreadPoolTaskScheduler reservationEventTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("atlas-order-inventory-event-relay-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
