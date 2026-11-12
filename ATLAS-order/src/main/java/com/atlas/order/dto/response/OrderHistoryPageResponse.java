package com.atlas.order.dto.response;

import com.atlas.order.persistence.state.OrderSource;
import com.atlas.order.persistence.state.OrderState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderHistoryPageResponse(List<Entry> orders, String nextCursor) {
    public OrderHistoryPageResponse {
        orders = List.copyOf(orders);
    }

    public record Entry(UUID orderId, UUID checkoutId, UUID sellerId, OrderSource source,
                        OrderState state, Instant createdAt) { }
}
