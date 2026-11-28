package com.atlas.order.dto.request;

import com.atlas.order.model.ReturnItemCondition;
import com.atlas.order.model.ReturnReason;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class OrderRequest {
    private OrderRequest() {
    }

    public record Refund(UUID sellerId, UUID orderId, String idempotencyKey, List<Item> items) {
        public record Item(UUID orderItemId, int quantity) {
        }
    }

    /** 운영자 환불. 품목은 서버가 남은 수량 전부로 정한다. {@code actor}는 감사 기록에 남는 운영자 식별자다. */
    public record OperatorRefund(UUID sellerId, UUID orderId, String idempotencyKey, String actor) {
    }

    public record Reorder(UUID orderId, String idempotencyKey, boolean confirmMerge) {
    }

    public record ReturnInspection(List<Item> items) {
        public record Item(UUID orderItemId, Result result) { }
        public enum Result { PASSED, FAILED }
    }

    /**
     * {@code reason}과 {@code note}는 선택이다(사유 이전 클라이언트 호환). {@code note}는 사유가 있을 때만 받고 저장 전
     * 앞뒤 공백을 지운다. 구매자가 쓴 신뢰할 수 없는 텍스트라 결정 규칙은 읽지 않는다(ADR-0110).
     */
    public record Return(String buyerRef, UUID orderId, String idempotencyKey, List<Item> items,
            ReturnReason reason, String note) {
        /** 분할 출고 반품은 출처 shipment를 지정한다. 유일한 적격 출처면 기존 요청처럼 생략할 수 있다. */
        public record Item(UUID orderItemId, int quantity, UUID shipmentId) {
            public Item(UUID orderItemId, int quantity) { this(orderItemId, quantity, null); }
        }
    }

    /** 판매자가 수령한 반품 줄마다 하나씩 적은 상태. 권고만 계산하고 저장하지 않는다. */
    public record ReturnRecommendation(List<Item> items) {
        public ReturnRecommendation {
            items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
        }

        public record Item(UUID orderItemId, ReturnItemCondition condition) { }
    }

    public record Shipment(
            UUID sellerId,
            UUID orderId,
            String idempotencyKey,
            String carrierCode,
            String trackingNumber,
            List<Item> items,
            UUID fulfillmentLocationId) {
        /** 단일 location 주문용. 출고 location은 주문의 할당에서 정한다. */
        public Shipment(UUID sellerId, UUID orderId, String idempotencyKey, String carrierCode,
                                    String trackingNumber, List<Item> items) {
            this(sellerId, orderId, idempotencyKey, carrierCode, trackingNumber, items, null);
        }

        public Shipment {
            items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
        }

        public record Item(UUID orderItemId, int quantity) { }
    }

    public record ReorderCart(String buyerRef, boolean confirmExistingCartMerge, List<Item> items) {
        public ReorderCart {
            items = items == null ? null : List.copyOf(items);
        }

        public record Item(UUID sellerId, UUID productId, String skuId, int quantity) {
        }
    }
}
