package com.atlas.order.persistence.state;

public enum OrderState {
    RESERVED,
    PAYMENT_SUBMITTED,
    PAYMENT_PENDING,
    PAYMENT_UNKNOWN,
    PAYMENT_VOID_PENDING,
    PAID,
    FAILED,
    EXPIRED,
    CANCEL_PENDING,
    CANCELLED
}
