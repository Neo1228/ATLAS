package com.atlas.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.application.service.CheckoutFinalizationWorker;
import com.atlas.application.service.LocationReservationWorker;
import com.atlas.application.service.LocationRestockWorker;
import com.atlas.application.service.PayPalWebhookRetryWorker;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulingConfigurationTest {
    @Test
    void longRunningRecoveryQueuesHaveSeparateBoundedSchedulers() throws Exception {
        SchedulingConfiguration configuration = new SchedulingConfiguration();
        var expiry = configuration.taskScheduler();
        var payment = configuration.paymentRecoveryTaskScheduler();
        var orderInventory = configuration.orderInventoryRecoveryTaskScheduler();
        var webhook = configuration.paypalWebhookRetryTaskScheduler();

        assertThat(expiry.getPoolSize()).isEqualTo(4);
        assertThat(payment.getPoolSize()).isEqualTo(1);
        assertThat(orderInventory.getPoolSize()).isEqualTo(3);
        assertThat(webhook.getPoolSize()).isEqualTo(1);
        assertThat(LocationReservationWorker.class.getMethod("poll")
                .getAnnotation(Scheduled.class).scheduler()).isEqualTo("orderInventoryRecoveryTaskScheduler");
        assertThat(CheckoutFinalizationWorker.class.getMethod("poll")
                .getAnnotation(Scheduled.class).scheduler()).isEqualTo("orderInventoryRecoveryTaskScheduler");
        assertThat(LocationRestockWorker.class.getMethod("poll")
                .getAnnotation(Scheduled.class).scheduler()).isEqualTo("orderInventoryRecoveryTaskScheduler");
        assertThat(PayPalWebhookRetryWorker.class.getMethod("retryDueEvents")
                .getAnnotation(Scheduled.class).scheduler()).isEqualTo("paypalWebhookRetryTaskScheduler");
        expiry.shutdown();
        payment.shutdown();
        orderInventory.shutdown();
        webhook.shutdown();
    }
}
