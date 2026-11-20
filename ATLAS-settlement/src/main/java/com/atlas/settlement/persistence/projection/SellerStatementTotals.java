package com.atlas.settlement.persistence.projection;

import java.math.BigInteger;

/**
 * 기간 흐름 요약.
 *
 * <p>{@code captured}/{@code refundReversal} 은 gross 기준 capture/refund 흐름이고,
 * {@code paidOut} 은 지급 확정으로 원장에서 빠져나간 금액이다. row 와 합계가 어긋나지 않도록
 * 다음 항등식이 성립한다({@code refundReversalAmountWon} 은 음수).
 *
 * <pre>
 * sum(row.signedAmountWon) = capturedAmountWon + refundReversalAmountWon - paidOutAmountWon
 * </pre>
 *
 * <p>수수료·보류는 gross 를 다시 더하지 않도록 별도 합계로 둔다. capture gross 는 이미
 * fee/reserve/seller 몫을 모두 포함하므로, fee·reserve 를 row 나 captured 에 더하면 이중 계산이다.
 *
 * @param capturedAmountWon capture 및 checkout allocation gross 합(지급 분개 제외)
 * @param refundReversalAmountWon refund 분개 합(음수)
 * @param netLedgerChangeWon captured + refundReversal. capture 기간 흐름이며 지급은 포함하지 않는다.
 * @param feeAmountWon 기간 중 PLATFORM_FEE 로 잡힌 수수료(대변 − 차변)
 * @param reserveAmountWon 기간 중 SELLER_RESERVE 순증(대변 − 차변). refund 로 차감되면 줄어든다.
 * @param paidOutAmountWon 기간 중 지급 확정 분개 gross 합(양수 크기). 원장 잔액을 줄인다.
 */
public record SellerStatementTotals(
        BigInteger capturedAmountWon,
        BigInteger refundReversalAmountWon,
        BigInteger netLedgerChangeWon,
        BigInteger feeAmountWon,
        BigInteger reserveAmountWon,
        BigInteger paidOutAmountWon) {
}
