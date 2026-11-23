package com.atlas.order.service;

import com.atlas.order.repository.OrderInventoryEventRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 workflow 이벤트 로그의 소비 진입점. 소비자 그룹마다 cursor를 따로 들고 같은 로그를 100% 읽는다.
 *
 * <p>order DB 안의 소비자(정산 원장, 가용 재고 projection, 감사)는 {@link #readSafe}로 읽고, 자기 쓰기와 같은
 * 트랜잭션에서 {@link #advance}를 호출하면 effectively-once가 된다. DB 밖의 소비자는 {@link #relay}가 내보낸
 * 전송 수단(Kafka)을 구독한다.
 */
@Service
public class OrderInventoryEventLogService {
    /** Kafka relay 자신의 cursor 그룹. */
    public static final String RELAY_GROUP = "kafka-relay";

    private final OrderInventoryEventRepository repository;

    public OrderInventoryEventLogService(OrderInventoryEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<OrderInventoryEvent> readSafe(String consumerGroup, int limit) {
        return repository.readSafe(consumerGroup, limit);
    }

    @Transactional
    public void advance(String consumerGroup, OrderInventoryEvent last) {
        repository.advance(consumerGroup, last);
    }

    /**
     * 안전하게 읽은 한 묶음을 publisher로 내보낸 뒤에만 cursor를 옮긴다(읽기, 전송, 전진 순서 = at-least-once).
     *
     * <p>이 메서드는 트랜잭션 밖에서 돈다. lease 선점, 읽기, cursor 전진이 각각 짧은 트랜잭션이고 Kafka 전송은 그 사이에
     * 트랜잭션 없이 실행되므로, 전송 대기가 xmin horizon을 붙잡지 않는다. publisher가 예외를 던지면 cursor는 그대로고
     * lease만 반납해 같은 묶음을 다시 보낸다. lease를 빼앗긴 뒤 끝난 전송은 fencing으로 cursor를 못 옮기고 결과를 버린다
     * (이벤트는 새 lease 보유자가 다시 보내므로 중복만 생기고 유실은 없다).
     *
     * @return cursor를 옮긴 이벤트 수. 다른 relay가 lease를 쥐고 있거나 lease를 잃었으면 0
     */
    public int relay(int batchSize, Duration leaseDuration, OrderInventoryEventPublisher publisher) {
        UUID leaseToken = UUID.randomUUID();
        if (!repository.claimLease(RELAY_GROUP, leaseToken, leaseDuration)) return 0;
        List<OrderInventoryEvent> batch;
        try {
            batch = repository.readSafe(RELAY_GROUP, batchSize);
            if (batch.isEmpty()) {
                repository.releaseLease(RELAY_GROUP, leaseToken);
                return 0;
            }
            publisher.publish(batch);
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

    public long lagEvents(String consumerGroup) {
        return repository.lagEvents(consumerGroup);
    }

    public double stalledSeconds() {
        return repository.stalledSeconds();
    }
}
