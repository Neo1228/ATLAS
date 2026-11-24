package com.atlas.order.service;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import java.util.List;

/**
 * 안전하게 읽은 이벤트 묶음을 order DB 밖으로 내보낸다. order 모듈은 전송 수단(Kafka 등)을 모른다.
 *
 * <p>모든 이벤트가 전달됐을 때만 정상 반환해야 한다. 일부라도 실패하면 예외를 던져야 하고,
 * 그러면 relay는 cursor를 옮기지 않고 같은 묶음을 다시 보낸다(at-least-once, 중복은 소비자가 event id로 거른다).
 */
public interface ReservationEventPublisher {

    void publish(List<ReservationWorkflowResponse.ReservationEvent> batch);
}
