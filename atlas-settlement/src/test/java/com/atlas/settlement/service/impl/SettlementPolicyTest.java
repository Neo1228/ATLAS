package com.atlas.settlement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 수수료·보류 분해의 경계와 반올림 규칙을 고정한다. */
class SettlementPolicyTest {
    @Test
    void splitsGrossIntoFeeReserveAndSellerWithFloorRounding() {
        SettlementPolicy policy = new SettlementPolicy(1000, 1000); // 각 10%

        SettlementPolicy.Split split = policy.split(10_000);

        assertThat(split.feeAmountWon()).isEqualTo(1_000);
        assertThat(split.reserveAmountWon()).isEqualTo(1_000);
        assertThat(split.sellerAmountWon()).isEqualTo(8_000);
    }

    @Test
    void floorRoundingNeverInflatesSellerBeyondGross() {
        SettlementPolicy policy = new SettlementPolicy(333, 333);

        // 1,001 * 333 / 10000 = 33.33 -> floor 33 (반올림이면 34)
        SettlementPolicy.Split split = policy.split(1_001);

        assertThat(split.feeAmountWon()).isEqualTo(33);
        assertThat(split.reserveAmountWon()).isEqualTo(33);
        assertThat(split.feeAmountWon() + split.reserveAmountWon() + split.sellerAmountWon())
                .isEqualTo(1_001);
    }

    @Test
    void zeroRatesKeepEntireAmountWithSeller() {
        SettlementPolicy.Split split = new SettlementPolicy(0, 0).split(5_000);

        assertThat(split.feeAmountWon()).isZero();
        assertThat(split.reserveAmountWon()).isZero();
        assertThat(split.sellerAmountWon()).isEqualTo(5_000);
    }

    @Test
    void rejectsCombinedRatesAboveOneHundredPercent() {
        assertThatThrownBy(() -> new SettlementPolicy(6000, 5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeRate() {
        assertThatThrownBy(() -> new SettlementPolicy(10_001, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementPolicy(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveGross() {
        SettlementPolicy policy = new SettlementPolicy(100, 100);
        assertThatThrownBy(() -> policy.split(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.split(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
