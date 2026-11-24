package com.atlas.order.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReservationWorkflowResponse {
    private ReservationWorkflowResponse() {
    }

    public record LocationReservation(
            UUID workflowId,
            UUID orderId,
            String idempotencyKey,
            String status,
            List<LocationResult> locations,
            Instant createdAt,
            Instant updatedAt,
            boolean replayed) {
        public record LocationResult(
                UUID fulfillmentLocationId,
                String status,
                UUID reservationId,
                Instant expiresAt,
                String lastErrorCode,
                List<Item> items) {
        }

        public record Item(UUID sellerId, String skuId, int quantity) {
        }
    }

    public record RemoteReservation(UUID reservationId, String status, Instant expiresAt) {
    }

    /** 주문 예약 이벤트 로그를 서비스 경계 밖으로 전달하는 응답 DTO. */
    public record ReservationEvent(long eventSeq, long txId, UUID workflowId, UUID orderId,
            long aggregateVersion, String eventType, String lastErrorCode, Instant occurredAt) {
        /** 소비자가 중복 전달을 거르는 식별자. */
        public String eventId() {
            return workflowId + ":" + aggregateVersion;
        }
    }
}
