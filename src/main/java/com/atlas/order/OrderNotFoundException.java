package com.atlas.order;

import java.util.UUID;

public final class OrderNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrderNotFoundException(UUID orderId) {
        super("Unknown order: " + orderId);
    }
}
