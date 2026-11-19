package com.atlas.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfiguration {
    // Issue #277: webhook 재시도 전용 job을 분리하고 동시 실행 슬롯을 4개로 둔다.
    private static final int SCHEDULER_POOL_SIZE = 4;

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("atlas-scheduler-");
        return scheduler;
    }

    @Bean(name = "paymentRecoveryTaskScheduler")
    ThreadPoolTaskScheduler paymentRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("atlas-payment-recovery-");
        return scheduler;
    }
}
