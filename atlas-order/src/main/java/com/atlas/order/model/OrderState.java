package com.atlas.order.model;

public enum OrderState {
    RESERVATION_PENDING,
    RESERVED,
    PAYMENT_SUBMITTED,
    PAYMENT_PENDING,
    INVENTORY_CLAIM_PENDING,
    PAYMENT_UNKNOWN,
    PAYMENT_VOID_PENDING,
    PAID,
    FAILED,
    EXPIRED,
    CANCEL_PENDING,
    CANCELLED;

    public boolean isTerminal() {
        return this == PAID || this == FAILED || this == EXPIRED || this == CANCELLED;
    }
}
