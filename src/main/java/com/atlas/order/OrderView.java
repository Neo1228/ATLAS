package com.atlas.order;

import com.atlas.admission.TicketStatus;
import com.atlas.inventory.ReservationStatus;
import com.atlas.payment.PaymentState;
import java.time.Instant;
import java.util.UUID;

public record OrderView(
        UUID orderId,
        UUID campaignId,
        UUID ticketId,
        UUID paymentAttemptId,
        OrderState state,
        Instant checkoutExpiresAt,
        ReservationStatus inventoryState,
        PaymentState paymentState,
        TicketStatus ticketState) {
}
