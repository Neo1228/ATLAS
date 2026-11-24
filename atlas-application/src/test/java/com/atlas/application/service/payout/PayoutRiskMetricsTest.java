package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.service.SettlementPayoutService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PayoutRiskMetricsTest {
    @Test
    void exposesAggregateRefundAndCreditExposureWithoutHighCardinalityTags() {
        SettlementPayoutService payouts = mock(SettlementPayoutService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PayoutRiskMetrics metrics = new PayoutRiskMetrics(payouts, registry);
        when(payouts.riskSnapshot()).thenReturn(new PayoutResponse.RiskSnapshot(2, 1_500, 3, 4_000));

        metrics.refresh();

        assertThat(registry.get("settlement.payout.negative_payable_seller_count").gauge().value()).isEqualTo(2);
        assertThat(registry.get("settlement.payout.negative_payable_amount_won").gauge().value()).isEqualTo(1_500);
        assertThat(registry.get("settlement.refund.pending_reservation_count").gauge().value()).isEqualTo(3);
        assertThat(registry.get("settlement.refund.pending_reservation_amount_won").gauge().value())
                .isEqualTo(4_000);
        assertThat(registry.get("settlement.refund.pending_reservation_count").gauge().getId().getTags())
                .isEmpty();
    }
}
