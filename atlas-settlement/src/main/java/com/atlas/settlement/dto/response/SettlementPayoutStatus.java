package com.atlas.settlement.dto.response;

/** Public payout status vocabulary, kept separate from the persistence state enum. */
public enum SettlementPayoutStatus {
    REQUESTED,
    APPROVED,
    DISPATCHING,
    SENT,
    UNKNOWN,
    UNCLAIMED,
    NEEDS_REVIEW,
    CANCELLED,
    CONFIRMED,
    FAILED
}
