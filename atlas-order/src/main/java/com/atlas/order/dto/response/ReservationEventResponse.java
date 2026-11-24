package com.atlas.order.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 주문 예약 이벤트 로그를 서비스 경계 밖으로 전달하는 응답 DTO. */
public record ReservationEventResponse(long eventSeq, long txId, UUID workflowId, UUID orderId,
        long aggregateVersion, String eventType, String lastErrorCode, Instant occurredAt) {
    /** 소비자가 중복 전달을 거르는 식별자. */
    public String eventId() {
        return workflowId + ":" + aggregateVersion;
    }
}
