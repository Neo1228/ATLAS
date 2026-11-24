package com.atlas.order.dto.response;

import com.atlas.admission.model.TicketStatus;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.order.model.FulfillmentStatus;
import com.atlas.order.model.OrderShipmentStatus;
import com.atlas.order.model.OrderSource;
import com.atlas.order.model.OrderState;
import com.atlas.payment.model.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderViewResponse {
    private OrderViewResponse() {
    }

    public record Order(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                                Instant checkoutExpiresAt, ReservationStatus inventoryState,
                                PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                                UUID fulfillmentLocationId, List<Item> items, long totalPriceWon,
                                String sellerCouponCode, long couponDiscountWon, String paymentApprovalUrl) {
        public Order {
            items = List.copyOf(items);
        }

        public Order(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                             Instant checkoutExpiresAt, ReservationStatus inventoryState,
                             PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                             UUID fulfillmentLocationId, List<Item> items, long totalPriceWon,
                             String sellerCouponCode, long couponDiscountWon) {
            this(orderId, campaignId, ticketId, paymentAttemptId, state, checkoutExpiresAt, inventoryState,
                    paymentState, ticketState, source, fulfillmentLocationId, items, totalPriceWon,
                    sellerCouponCode, couponDiscountWon, null);
        }

        public Order(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                             Instant checkoutExpiresAt, ReservationStatus inventoryState,
                             PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                             UUID fulfillmentLocationId, List<Item> items, long totalPriceWon) {
            this(orderId, campaignId, ticketId, paymentAttemptId, state, checkoutExpiresAt, inventoryState,
                    paymentState, ticketState, source, fulfillmentLocationId, items, totalPriceWon, null, 0, null);
        }
    }

    /**
     * 주문 한 줄.
     *
     * <p>{@code orderItemId} 는 배송·환불·반품 검수 API 가 모두 요구하는 식별자다. 이 필드가
     * 응답에 없으면 판매자는 HTTP 만으로 이행을 시작할 수 없다. 실제로 이 저장소의 통합
     * 테스트들이 이 값을 JDBC 로 직접 조회해 우회하고 있었다.
     */
    public record Item(UUID orderItemId, UUID productId, UUID sellerId, String skuId,
                                    String productName, int quantity, long unitPriceWon,
                                    long couponDiscountWon, long lineTotalWon) {
        public Item(UUID productId, UUID sellerId, String skuId, String productName,
                                 int quantity, long unitPriceWon) {
            this(null, productId, sellerId, skuId, productName, quantity, unitPriceWon, 0,
                    Math.multiplyExact(unitPriceWon, quantity));
        }
    }

    public record Fulfillment(UUID orderId, FulfillmentStatus status, Instant createdAt,
                                           Instant updatedAt, Instant inProgressAt, Instant shippedAt,
                                           Instant deliveredAt) {
    }

    public record Shipment(
            UUID shipmentId,
            UUID orderId,
            String idempotencyKey,
            OrderShipmentStatus status,
            String carrierCode,
            String trackingNumber,
            Instant shippedAt,
            Instant deliveredAt,
            List<Item> items,
            UUID fulfillmentLocationId) {
        public Shipment(UUID shipmentId, UUID orderId, String idempotencyKey, OrderShipmentStatus status,
                                     String carrierCode, String trackingNumber, Instant shippedAt, Instant deliveredAt,
                                     List<Item> items) {
            this(shipmentId, orderId, idempotencyKey, status, carrierCode, trackingNumber, shippedAt, deliveredAt,
                    items, null);
        }

        public Shipment {
            items = List.copyOf(items);
        }

        public record Item(UUID orderItemId, int quantity) { }
    }

    public record HistoryPage(List<Entry> orders, String nextCursor) {
        public HistoryPage {
            orders = List.copyOf(orders);
        }

        public record Entry(UUID orderId, UUID checkoutId, UUID sellerId, OrderSource source,
                            OrderState state, Instant createdAt) { }
    }

    /** 판매자가 배송·환불을 구성할 수 있도록 자기 주문의 이행 필드만 제공한다. */
    public record SellerOrderDetail(UUID orderId, UUID sellerId, OrderSource source, OrderState state,
                                            Instant createdAt, Instant checkoutExpiresAt,
                                            ReservationStatus inventoryState, UUID fulfillmentLocationId,
                                            List<Item> items, long totalPriceWon,
                                            String sellerCouponCode, long couponDiscountWon) {
        public SellerOrderDetail {
            items = List.copyOf(items);
        }
    }
}
