package com.atlas.settlement.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * capture 금액을 수수료·보류금·판매자 몫으로 분해한다.
 *
 * <p>정책(수수료율·보류율)은 설정에서 읽고, 적용 결과와 적용 시점 요율을 함께 돌려준다.
 * 호출자가 그 요율을 journal 에 스냅샷하므로, 나중에 정책이 바뀌어도 과거 분개는 재계산되지
 * 않는다. 환율 스냅샷과 같은 원칙이다.
 *
 * <p>버림(floor)을 쓴다. 판매자 몫이 반올림으로 gross 를 넘는 일을 막고, 플랫폼이 원 단위
 * 미만을 임의로 가져가지 않게 하기 위해서다.
 */
@Component
public class SettlementPolicy {
    /** 10,000 bps = 100%. */
    public static final int BPS_DENOMINATOR = 10_000;

    private final int feeRateBps;
    private final int reserveRateBps;

    public SettlementPolicy(
            @Value("${atlas.settlement.fee-rate-bps:0}") int feeRateBps,
            @Value("${atlas.settlement.reserve-rate-bps:0}") int reserveRateBps) {
        if (feeRateBps < 0 || feeRateBps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException("settlement fee rate must be 0..10000 bps");
        }
        if (reserveRateBps < 0 || reserveRateBps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException("settlement reserve rate must be 0..10000 bps");
        }
        // 수수료와 보류를 합쳐 gross 를 넘으면 판매자 몫이 음수가 된다.
        if ((long) feeRateBps + reserveRateBps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException(
                    "settlement fee + reserve rate must not exceed 10000 bps");
        }
        this.feeRateBps = feeRateBps;
        this.reserveRateBps = reserveRateBps;
    }

    public int feeRateBps() {
        return feeRateBps;
    }

    public int reserveRateBps() {
        return reserveRateBps;
    }

    /**
     * capture 금액을 분해한다.
     *
     * @param grossAmountWon 확인된 capture 총액(양수)
     * @return 수수료·보류·판매자 몫과 적용 요율
     */
    public Split split(long grossAmountWon) {
        if (grossAmountWon <= 0) {
            throw new IllegalArgumentException("capture amount must be positive");
        }
        long fee = applyRate(grossAmountWon, feeRateBps);
        long reserve = applyRate(grossAmountWon, reserveRateBps);
        long seller = grossAmountWon - fee - reserve;
        if (seller < 0) {
            // 정책 검증에서 막히지만, gross 가 매우 작을 때도 방어한다.
            throw new IllegalStateException("settlement split produced a negative seller amount");
        }
        return new Split(grossAmountWon, fee, reserve, seller, feeRateBps, reserveRateBps);
    }

    private static long applyRate(long amountWon, int rateBps) {
        if (rateBps == 0) return 0;
        return BigDecimal.valueOf(amountWon)
                .multiply(BigDecimal.valueOf(rateBps))
                .divide(BigDecimal.valueOf(BPS_DENOMINATOR), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    /** 한 capture 의 분해 결과. 금액과 적용 요율을 함께 들고 다닌다. */
    public record Split(long grossAmountWon, long feeAmountWon, long reserveAmountWon,
            long sellerAmountWon, int feeRateBps, int reserveRateBps) {
        public Split {
            Objects.requireNonNull(grossAmountWon > 0 ? Boolean.TRUE : null);
            if (feeAmountWon + reserveAmountWon + sellerAmountWon != grossAmountWon) {
                throw new IllegalArgumentException("settlement split must sum to gross");
            }
        }
    }
}
