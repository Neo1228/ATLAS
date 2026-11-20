package com.atlas.settlement.dto.response;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SellerSettlementStatementResponse(
        UUID sellerId,
        Instant from,
        Instant to,
        Summary totals,
        List<Entry> items,
        String nextCursor) {

    /**
     * 기간 요약과 현재 잔액.
     *
     * <p>기간 값(captured/refund/net, periodFee/periodReserve, paidOut)은 요청 기간의 흐름이고,
     * balance 계열은 <b>현재</b> 잔액이다. 판매자가 "이번 달 얼마 벌었나"와 "지금 얼마 받을 수
     * 있나"를 구분해 볼 수 있어야 한다.
     *
     * <p>수수료·보류는 기간 합계(periodFee/periodReserve)와 현재 잔액(fee/reserve)을 모두
     * 노출한다. 한쪽만 있으면 "이 기간에 얼마가 수수료로 빠졌나" 또는 "지금 보류가 얼마인가" 중
     * 하나를 답할 수 없다.
     *
     * <p>gross 이중 계산 금지: captured 는 gross 이고 gross 안에 fee/reserve/seller 몫이 모두
     * 들어 있다. 그래서 periodFee/periodReserve 는 captured 에 더하지 않고 별도 필드로만 둔다.
     *
     * <p>같은 이유로 paidOut 도 netLedgerChange 에 더하지 않는다. paidOut 은 PAYOUT row 의 합
     * (부호 반전)이며 특정 기간에 한정되고, confirmedPayoutAmountWon 은 기간과 무관한 누적
     * 확정 지급액이다. 어떤 기간의 row 합은 captured + refundReversal − paidOut 이다.
     */
    public record Summary(
            BigInteger capturedAmountWon,
            BigInteger refundReversalAmountWon,
            BigInteger netLedgerChangeWon,
            BigInteger periodFeeAmountWon,
            BigInteger periodReserveAmountWon,
            BigInteger paidOutAmountWon,
            BigInteger feeAmountWon,
            BigInteger reserveAmountWon,
            BigInteger payableAmountWon,
            BigInteger pendingPayoutAmountWon,
            BigInteger confirmedPayoutAmountWon) {
    }

    public record Entry(UUID journalId, UUID orderId, UUID sourceId, EntryType type, Instant recordedAt,
                        long signedAmountWon) {
    }

    /**
     * 명세 row 종류. journal 의 source 로 결정된다.
     *
     * <p>{@code PAYOUT} 은 지급 확정 분개다. 지급은 판매자의 지급 가능 잔액을 줄이므로
     * signed amount 가 음수로 내려간다. capture/refund 와 달리 gross 를 늘리지 않는다.
     */
    public enum EntryType {
        ORDER_CAPTURE,
        CHECKOUT_ALLOCATION,
        REFUND_REVERSAL,
        PAYOUT
    }
}
