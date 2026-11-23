package com.atlas.order.persistence.state;

public enum BuyerCheckoutState {
    RESERVATION_PENDING,
    RESERVED,
    PAYMENT_PENDING,
    INVENTORY_CLAIM_PENDING,
    PAYMENT_UNKNOWN,
    CANCEL_PENDING,
    PAID,
    FAILED,
    CANCELLED,
    EXPIRED
}
