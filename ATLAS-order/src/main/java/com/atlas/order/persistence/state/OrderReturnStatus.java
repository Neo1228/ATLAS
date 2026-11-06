package com.atlas.order.persistence.state;

public enum OrderReturnStatus {
    REQUESTED,
    RECEIVED,
    REFUND_RECORDED,
    PARTIALLY_RECORDED,
    INSPECTION_FAILED
}
