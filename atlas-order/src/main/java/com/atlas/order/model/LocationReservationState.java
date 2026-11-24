package com.atlas.order.model;

public enum LocationReservationState {
    PENDING,
    RETRY_PENDING,
    RESERVED,
    CLAIMED,
    FAILED,
    RELEASE_PENDING,
    RELEASED
}
