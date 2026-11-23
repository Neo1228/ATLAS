package com.atlas.order.persistence.state;

public enum LocationReservationWorkflowState {
    PENDING,
    RETRY_PENDING,
    RESERVED,
    CLAIMED,
    COMPENSATING,
    COMPENSATION_REQUIRED,
    CANCEL_PENDING,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW
}
