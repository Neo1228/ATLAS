package com.atlas.order.persistence.state;

public enum LocationReservationState {
    PENDING,
    RETRY_PENDING,
    RESERVED,
    CLAIMED,
    FAILED,
    RELEASE_PENDING,
    RELEASED
}
