package com.atlas.admission.persistence.state;

public enum CheckoutSlotReleaseReason {
    INITIAL_AUTHORIZE_SUBMITTED,
    CHECKOUT_WINDOW_EXPIRED,
    INVENTORY_UNAVAILABLE,
    PRODUCT_NOT_ORDERABLE,
    BUYER_CANCELLED
}
