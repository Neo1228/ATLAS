package com.atlas.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.IdGenerator;
import org.springframework.util.JdkIdGenerator;

/** 결제 모듈이 쓰는 공용 빈. */
@Configuration
public class PaymentConfiguration {
    /** 결제 시도와 환불 작업의 식별자를 만든다. 테스트는 결정적인 구현을 직접 넘긴다. */
    @Bean
    IdGenerator idGenerator() {
        return new JdkIdGenerator();
    }
}
