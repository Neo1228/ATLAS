package com.atlas.order;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.service.ReservationEventPublisher;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 앞의 몇 번은 실제 전송을 마친 뒤 예외를 던져 relay가 cursor를 옮기지 못하게 한다. */
final class FailingAfterSendPublisher implements ReservationEventPublisher {
    private final ReservationEventPublisher delegate;
    private final AtomicInteger failuresLeft;

    FailingAfterSendPublisher(ReservationEventPublisher delegate, int failures) {
        this.delegate = delegate;
        this.failuresLeft = new AtomicInteger(failures);
    }

    @Override
    public void publish(List<ReservationWorkflowResponse.ReservationEvent> batch) {
        delegate.publish(batch);
        if (failuresLeft.getAndDecrement() > 0) throw new IllegalStateException("forced publish failure");
    }
}
