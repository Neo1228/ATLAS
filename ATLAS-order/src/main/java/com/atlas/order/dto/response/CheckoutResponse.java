package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.BuyerCheckoutState;
import com.atlas.payment.persistence.state.PaymentState;
import java.util.List;
import java.util.UUID;

public record CheckoutResponse(UUID checkoutId, String buyerRef, BuyerCheckoutState state,
                               long totalWon, List<OrderResponse> orders,
                               UUID paymentAttemptId, PaymentState paymentState) {
    public CheckoutResponse {
        orders = List.copyOf(orders);
    }

    public CheckoutResponse(UUID checkoutId, String buyerRef, BuyerCheckoutState state,
                            long totalWon, List<OrderResponse> orders) {
        this(checkoutId, buyerRef, state, totalWon, orders, null, PaymentState.NOT_STARTED);
    }
}
