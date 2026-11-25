package com.atlas.application.service;

import com.atlas.order.service.OrderReturnService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 검수 마감이 지난 반품 수를 운영자에게 알리는 gauge(ADR-0089). Seller·반품·주문 label이 없는 집계 값이며,
 * 이 값은 알림일 뿐 반품 상태나 환불을 바꾸지 않는다.
 */
@Component
public class ReturnInspectionMetrics {
    private final OrderReturnService returns;
    private final MeterRegistry meters;
    private final AtomicLong overdue = new AtomicLong();

    public ReturnInspectionMetrics(OrderReturnService returns, MeterRegistry meters) {
        this.returns = Objects.requireNonNull(returns);
        this.meters = Objects.requireNonNull(meters);
        meters.gauge("order.return.inspection_overdue_count", overdue);
    }

    @Scheduled(fixedDelayString = "${atlas.order.return.inspection-metrics.poll-interval:PT1M}",
            initialDelayString = "${atlas.order.return.inspection-metrics.initial-delay:PT30S}")
    public void refresh() {
        try {
            overdue.set(returns.countInspectionOverdue());
        } catch (RuntimeException exception) {
            meters.counter("order.return.inspection_metrics.refresh_failures").increment();
        }
    }
}
