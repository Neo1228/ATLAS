package com.atlas.order.persistence.state;

public enum InventoryLocationReservationState {
    PENDING,
    RETRY_PENDING,
    RESERVED,
    CLAIMED,
    FAILED,
    RELEASE_PENDING,
    RELEASED
}
