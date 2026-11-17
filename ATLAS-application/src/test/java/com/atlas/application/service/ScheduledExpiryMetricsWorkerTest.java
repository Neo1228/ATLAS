package com.atlas.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.admission.service.AdmissionService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ScheduledExpiryMetricsWorkerTest {
    private final AdmissionService admission = mock(AdmissionService.class);
    private final OrderService orders = mock(OrderService.class);
    private final CheckoutPaymentService checkouts = mock(CheckoutPaymentService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScheduledExpiryMetricsWorker worker =
            new ScheduledExpiryMetricsWorker(admission, orders, checkouts, meters);

    @Test
    void recordsDurationRunsAndChangedItemsForSuccessfulSweep() {
        when(admission.expireDueTickets()).thenReturn(7);

        worker.expireAdmissionTickets();

        assertThat(meters.counter("atlas.expiry.sweep.runs", "workflow", "admission", "outcome", "success")
                .count()).isEqualTo(1);
        assertThat(meters.counter("atlas.expiry.sweep.changed", "workflow", "admission").count()).isEqualTo(7);
        assertThat(meters.timer("atlas.expiry.sweep.duration", "workflow", "admission").count()).isEqualTo(1);
    }

    @Test
    void recordsQueryFailureAndContinuesOnLaterSchedulerTick() {
        when(admission.expireDueTickets())
                .thenThrow(new DataAccessResourceFailureException("simulated query failure"));

        worker.expireAdmissionTickets();
        worker.expireAdmissionTickets();

        assertThat(meters.counter("atlas.expiry.sweep.runs", "workflow", "admission", "outcome", "failure")
                .count()).isEqualTo(2);
        assertThat(meters.timer("atlas.expiry.sweep.duration", "workflow", "admission").count()).isEqualTo(2);
    }

    @Test
    void recordsOrderAndCheckoutWorkflowsWithLowCardinalityTags() {
        when(orders.expireDueOrders()).thenReturn(2);
        when(checkouts.expireDueCheckouts()).thenReturn(3);

        worker.expireOrders();
        worker.expireCheckouts();

        assertThat(meters.counter("atlas.expiry.sweep.changed", "workflow", "orders").count()).isEqualTo(2);
        assertThat(meters.counter("atlas.expiry.sweep.changed", "workflow", "checkouts").count()).isEqualTo(3);
        assertThat(meters.timer("atlas.expiry.sweep.duration", "workflow", "orders").count()).isEqualTo(1);
        assertThat(meters.timer("atlas.expiry.sweep.duration", "workflow", "checkouts").count()).isEqualTo(1);
    }

}
