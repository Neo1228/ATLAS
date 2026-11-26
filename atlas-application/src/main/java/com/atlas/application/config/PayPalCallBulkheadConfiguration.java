package com.atlas.application.config;

import com.atlas.payment.provider.paypal.PayPalCallBulkhead;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PayPal 호출 상한(ADR-0098)을 만들고 거절 수를 Micrometer 계수기로 연결한다. 결제 모듈이 지표 라이브러리를 모르게 한다. */
@Configuration
@ConditionalOnProperty(name = "atlas.payment.provider", havingValue = "paypal")
public class PayPalCallBulkheadConfiguration {

    @Bean
    PayPalCallBulkhead payPalCallBulkhead(MeterRegistry meters,
            @Value("${atlas.payment.paypal.bulkhead.max-concurrent-calls:0}") int maxConcurrentCalls) {
        Counter rejected = Counter.builder("atlas.payment.paypal.bulkhead.rejected")
                .description("PayPal 호출 상한 초과로 보내기 전에 거절한 요청 스레드 호출 수")
                .register(meters);
        return new PayPalCallBulkhead(maxConcurrentCalls, rejected::increment);
    }
}
