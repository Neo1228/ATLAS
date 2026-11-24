package com.atlas.order.dto.request;

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

    public record Reorder(UUID orderId, String idempotencyKey, boolean confirmMerge) {
    }

    public record ReturnInspection(List<Item> items) {
        public record Item(UUID orderItemId, Result result) { }
        public enum Result { PASSED, FAILED }
    }

    public record Return(String buyerRef, UUID orderId, String idempotencyKey, List<Item> items) {
        /** 분할 출고 반품은 출처 shipment를 지정한다. 유일한 적격 출처면 기존 요청처럼 생략할 수 있다. */
        public record Item(UUID orderItemId, int quantity, UUID shipmentId) {
            public Item(UUID orderItemId, int quantity) { this(orderItemId, quantity, null); }
        }
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
