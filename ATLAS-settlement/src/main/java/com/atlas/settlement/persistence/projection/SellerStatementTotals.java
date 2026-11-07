package com.atlas.settlement.persistence.projection;

import java.math.BigInteger;

public record SellerStatementTotals(
        BigInteger capturedAmountWon,
        BigInteger refundReversalAmountWon,
        BigInteger netLedgerChangeWon) {
}
