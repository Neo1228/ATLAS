package com.atlas.application.service;

import com.atlas.admission.service.AdmissionService;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledExpiryMetricsWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledExpiryMetricsWorker.class);
    private static final String RUNS_METRIC = "atlas.expiry.sweep.runs";
    private static final String CHANGED_METRIC = "atlas.expiry.sweep.changed";
    private static final String DURATION_METRIC = "atlas.expiry.sweep.duration";
    private static final long FAILURE_LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final AdmissionService admission;
    private final OrderService orders;
    private final CheckoutPaymentService checkouts;
    private final MeterRegistry meters;
    private final Map<String, Long> nextFailureLogNanos = new HashMap<>();

    public ScheduledExpiryMetricsWorker(AdmissionService admission, OrderService orders,
            CheckoutPaymentService checkouts, MeterRegistry meters) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.checkouts = Objects.requireNonNull(checkouts, "checkouts");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Scheduled(fixedDelayString = "${atlas.admission.expiry-poll-interval:PT1S}")
    public void expireAdmissionTickets() {
        record("admission", admission::expireDueTickets);
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public void expireOrders() {
        record("orders", orders::expireDueOrders);
    }

    @Scheduled(fixedDelayString = "${atlas.orders.expiry-poll-interval:PT1S}")
    public void expireCheckouts() {
        record("checkouts", checkouts::expireDueCheckouts);
    }

    private void record(String workflow, IntSupplier sweep) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            int changed = sweep.getAsInt();
            Counter changedCounter = Counter.builder(CHANGED_METRIC).tag("workflow", workflow).register(meters);
            if (changed > 0) changedCounter.increment(changed);
        } catch (RuntimeException exception) {
            outcome = "failure";
            warnFailure(workflow, exception);
        } finally {
            Counter.builder(RUNS_METRIC).tag("workflow", workflow).tag("outcome", outcome)
                    .register(meters).increment();
            sample.stop(Timer.builder(DURATION_METRIC).tag("workflow", workflow).register(meters));
        }
    }

    private synchronized void warnFailure(String workflow, RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.getOrDefault(workflow, 0L);
        if (next == 0 || now - next >= 0) {
            nextFailureLogNanos.put(workflow, now + FAILURE_LOG_INTERVAL_NANOS);
            LOGGER.warn("Scheduled expiry sweep failed; workflow={} failureType={}", workflow,
                    exception.getClass().getSimpleName());
        }
    }
}
