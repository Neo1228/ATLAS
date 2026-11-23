package com.atlas.order.service;

import java.time.Instant;
import java.util.UUID;

/**
 * 예약 workflow 상태 변경 이벤트 한 건.
 *
 * <p>{@code txId}는 이벤트를 쓴 트랜잭션의 xid8이고, 소비 순서는 ({@code txId}, {@code eventSeq})다.
 * {@code aggregateVersion}은 workflow 안에서 1부터 빈틈없이 늘어나므로 소비자는
 * {@code workflowId:aggregateVersion}을 멱등 키로, aggregateVersion을 낡은 이벤트 판별에 쓴다.
 */
public record OrderInventoryEvent(long eventSeq, long txId, UUID workflowId, UUID orderId, long aggregateVersion,
        String eventType, String lastErrorCode, Instant occurredAt) {

    /** 소비자가 중복 전달을 걸러내는 키. */
    public String eventId() {
        return workflowId + ":" + aggregateVersion;
    }
}
