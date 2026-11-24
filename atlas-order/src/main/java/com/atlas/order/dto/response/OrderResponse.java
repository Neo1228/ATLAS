package com.atlas.order.dto.response;

import com.atlas.admission.model.TicketStatus;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.order.model.OrderSource;
import com.atlas.order.model.OrderState;
import com.atlas.payment.model.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                            Instant checkoutExpiresAt, ReservationStatus inventoryState,
                            PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                            UUID fulfillmentLocationId, List<OrderItemResponse> items, long totalPriceWon,
                            String sellerCouponCode, long couponDiscountWon, String paymentApprovalUrl) {
    public OrderResponse {
        items = List.copyOf(items);
    }

    public OrderResponse(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                         Instant checkoutExpiresAt, ReservationStatus inventoryState,
                         PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                         UUID fulfillmentLocationId, List<OrderItemResponse> items, long totalPriceWon,
                         String sellerCouponCode, long couponDiscountWon) {
        this(orderId, campaignId, ticketId, paymentAttemptId, state, checkoutExpiresAt, inventoryState,
                paymentState, ticketState, source, fulfillmentLocationId, items, totalPriceWon,
                sellerCouponCode, couponDiscountWon, null);
    }

    public OrderResponse(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                         Instant checkoutExpiresAt, ReservationStatus inventoryState,
                         PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                         UUID fulfillmentLocationId, List<OrderItemResponse> items, long totalPriceWon) {
        this(orderId, campaignId, ticketId, paymentAttemptId, state, checkoutExpiresAt, inventoryState,
                paymentState, ticketState, source, fulfillmentLocationId, items, totalPriceWon, null, 0, null);
    }
}
