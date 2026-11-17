package com.atlas.payment.persistence.state;

public enum PaymentRecoveryJobStatus {
    PENDING,
    WAITING_FOR_BUYER,
    COMPLETED,
    NEEDS_REVIEW
}
