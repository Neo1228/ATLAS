package com.atlas.order.service.impl;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.persistence.projection.ReservationEvent;
import com.atlas.order.repository.ReservationEventRepository;
import com.atlas.order.service.ReservationEventPublisher;
import com.atlas.order.service.ReservationEventService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 workflow 이벤트 로그의 소비 진입점. 소비자 그룹마다 cursor를 따로 들고 같은 로그를 100% 읽는다.
 *
 * <p>order DB 안의 소비자(정산 원장, 가용 재고 projection, 감사)는 {@link #readEvents}로 읽고, 자기 쓰기와 같은
 * 트랜잭션에서 {@link #advance}를 호출하면 effectively-once가 된다. DB 밖의 소비자는 {@link #relay}가 내보낸
 * 전송 수단(Kafka)을 구독한다.
 */
@Service
public class ReservationEventServiceImpl implements ReservationEventService {

    private final ReservationEventRepository repository;

    public ReservationEventServiceImpl(ReservationEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @Override
    public List<ReservationWorkflowResponse.ReservationEvent> readEvents(String consumerGroup, int limit) {
        return repository.readEvents(consumerGroup, limit).stream()
                .map(ReservationEventServiceImpl::toResponse)
                .toList();
    }

    @Transactional
    @Override
    public void advance(String consumerGroup, ReservationWorkflowResponse.ReservationEvent last) {
        repository.advance(consumerGroup, toProjection(last));
    }

    @Override
    public int relay(int batchSize, Duration leaseDuration, ReservationEventPublisher publisher) {
        UUID leaseToken = UUID.randomUUID();
        if (!repository.claimLease(RELAY_GROUP, leaseToken, leaseDuration)) return 0;
        List<ReservationEvent> batch;
        try {
            batch = repository.readEvents(RELAY_GROUP, batchSize);
            if (batch.isEmpty()) {
                repository.releaseLease(RELAY_GROUP, leaseToken);
                return 0;
            }
            publisher.publish(batch.stream().map(ReservationEventServiceImpl::toResponse).toList());
        } catch (RuntimeException exception) {
            releaseAfterFailure(leaseToken, exception);
            throw exception;
        }
        return repository.advanceFenced(RELAY_GROUP, batch.get(batch.size() - 1), leaseToken) ? batch.size() : 0;
    }

    private void releaseAfterFailure(UUID leaseToken, RuntimeException failure) {
        try {
            repository.releaseLease(RELAY_GROUP, leaseToken);
        } catch (RuntimeException releaseFailure) {
            // lease는 만료로도 풀리므로 반납 실패는 원래 실패에 덧붙이기만 한다.
            failure.addSuppressed(releaseFailure);
        }
    }

    private static ReservationWorkflowResponse.ReservationEvent toResponse(ReservationEvent event) {
        return new ReservationWorkflowResponse.ReservationEvent(event.eventSeq(), event.txId(), event.workflowId(), event.orderId(),
                event.aggregateVersion(), event.eventType(), event.lastErrorCode(), event.occurredAt());
    }

    private static ReservationEvent toProjection(ReservationWorkflowResponse.ReservationEvent event) {
        return new ReservationEvent(event.eventSeq(), event.txId(), event.workflowId(), event.orderId(),
                event.aggregateVersion(), event.eventType(), event.lastErrorCode(), event.occurredAt());
    }

    @Override
    public long lagEvents(String consumerGroup) {
        return repository.lagEvents(consumerGroup);
    }

    @Override
    public double stalledSeconds() {
        return repository.stalledSeconds();
    }
}
