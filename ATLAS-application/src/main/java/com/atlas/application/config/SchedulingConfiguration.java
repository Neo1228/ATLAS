package com.atlas.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfiguration {
    // Expiry/auth sweep용 기본 풀. 결제 recovery, 주문 재고 recovery, webhook retry는 별도 풀을 쓴다.
    private static final int SCHEDULER_POOL_SIZE = 4;

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("atlas-scheduler-");
        waitForInFlightTasks(scheduler);
        return scheduler;
    }

    @Bean(name = "paymentRecoveryTaskScheduler")
    ThreadPoolTaskScheduler paymentRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("atlas-payment-recovery-");
        waitForInFlightTasks(scheduler);
        return scheduler;
    }

    /** coordinator·checkout finalizer·환불 재입고를 각각 하나씩 실행하는 격리된 worker pool. */
    @Bean(name = "orderInventoryRecoveryTaskScheduler")
    ThreadPoolTaskScheduler orderInventoryRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("atlas-order-inventory-recovery-");
        waitForInFlightTasks(scheduler);
        return scheduler;
    }

    /** PayPal webhook DB 재시도를 expiry/auth scheduler와 분리한다. */
    @Bean(name = "paypalWebhookRetryTaskScheduler")
    ThreadPoolTaskScheduler paypalWebhookRetryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("atlas-paypal-webhook-retry-");
        waitForInFlightTasks(scheduler);
        return scheduler;
    }

    private static void waitForInFlightTasks(ThreadPoolTaskScheduler scheduler) {
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
    }

}
