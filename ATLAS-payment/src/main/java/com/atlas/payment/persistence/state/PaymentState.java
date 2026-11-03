package com.atlas.payment.persistence.state;

public enum PaymentState {
    NOT_STARTED,
    AUTHORIZATION_PENDING,
    AUTHORIZED,
    CAPTURE_PENDING,
    CAPTURED,
    DECLINED,
    UNKNOWN,
    VOID_PENDING,
    VOIDED
}
