package com.atlas.settlement.model;

import java.math.BigInteger;

/**
 * 판매자 계정 잔액.
 *
 * <p>{@code BigInteger} 를 쓰는 이유: 기존 statement totals 와 같은 이유다. 여러 capture 의
 * 합을 {@code long} 으로 받으면 큰 금액에서 오버플로가 나고, 그 예외가 원인을 숨긴다. 잔액은
 * 지급 금액을 결정하므로 정확한 정수 연산이 필요하다.
 *
 * @param payableAmountWon 지급 가능 잔액(SELLER_PAYABLE 대변 − 차변)
 * @param reserveAmountWon 보류 잔액(SELLER_RESERVE 대변 − 차변)
 * @param feeAmountWon 누적 플랫폼 수수료(PLATFORM_FEE 대변)
 */
public record SellerBalance(
        BigInteger payableAmountWon,
        BigInteger reserveAmountWon,
        BigInteger feeAmountWon) {
}
