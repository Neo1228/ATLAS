package com.atlas.order.dto.response;

import com.atlas.admission.persistence.state.TicketStatus;
import com.atlas.inventory.persistence.state.ReservationStatus;
import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import com.atlas.payment.persistence.state.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                            Instant checkoutExpiresAt, ReservationStatus inventoryState,
                            PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                            UUID fulfillmentLocationId, List<OrderItemResponse> items, long totalPriceWon,
                            String sellerCouponCode, long couponDiscountWon) {
    public OrderResponse {
        items = List.copyOf(items);
    }

    public OrderResponse(UUID orderId, UUID campaignId, UUID ticketId, UUID paymentAttemptId, OrderState state,
                         Instant checkoutExpiresAt, ReservationStatus inventoryState,
                         PaymentState paymentState, TicketStatus ticketState, OrderSource source,
                         UUID fulfillmentLocationId, List<OrderItemResponse> items, long totalPriceWon) {
        this(orderId, campaignId, ticketId, paymentAttemptId, state, checkoutExpiresAt, inventoryState,
                paymentState, ticketState, source, fulfillmentLocationId, items, totalPriceWon, null, 0);
    }
}
