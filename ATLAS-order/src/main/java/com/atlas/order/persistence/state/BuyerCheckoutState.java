package com.atlas.order.persistence.state;

public enum BuyerCheckoutState {
    RESERVED,
    PAYMENT_PENDING,
    PAYMENT_UNKNOWN,
    CANCEL_PENDING,
    PAID,
    FAILED,
    CANCELLED,
    EXPIRED
}
