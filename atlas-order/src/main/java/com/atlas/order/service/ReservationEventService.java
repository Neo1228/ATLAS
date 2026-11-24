package com.atlas.order.service;

import com.atlas.order.dto.response.ReservationEventResponse;
import java.time.Duration;
import java.util.List;

public interface ReservationEventService {
    /** Kafka relay가 cursor를 소유하는 consumer group 이름. */
    String RELAY_GROUP = "kafka-relay";

    List<ReservationEventResponse> readEvents(String consumerGroup, int limit);

    void advance(String consumerGroup, ReservationEventResponse last);

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
    int relay(int batchSize, Duration leaseDuration, ReservationEventPublisher publisher);

    long lagEvents(String consumerGroup);

    double stalledSeconds();
}
