package com.atlas.application.service.payout;

import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.service.SettlementPayoutService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Aggregated financial-risk metrics with no Seller, payout, or recipient labels. */
@Component
public class PayoutRiskMetrics {
    private final SettlementPayoutService payouts;
    private final MeterRegistry meters;
    private final AtomicReference<PayoutResponse.RiskSnapshot> snapshot = new AtomicReference<>(
            new PayoutResponse.RiskSnapshot(0, 0, 0, 0));

    public PayoutRiskMetrics(SettlementPayoutService payouts, MeterRegistry meters) {
        this.payouts = Objects.requireNonNull(payouts);
        this.meters = Objects.requireNonNull(meters);
        meters.gauge("settlement.payout.negative_payable_seller_count", snapshot,
                value -> value.get().negativePayableSellerCount());
        meters.gauge("settlement.payout.negative_payable_amount_won", snapshot,
                value -> value.get().negativePayableAmountWon());
        meters.gauge("settlement.refund.pending_reservation_count", snapshot,
                value -> value.get().pendingRefundReservationCount());
        meters.gauge("settlement.refund.pending_reservation_amount_won", snapshot,
                value -> value.get().pendingRefundReservationAmountWon());
    }

    @Scheduled(fixedDelayString = "${atlas.payment.paypal.payouts.risk-metrics.poll-interval:PT30S}",
            initialDelayString = "${atlas.payment.paypal.payouts.risk-metrics.initial-delay:PT10S}")
    public void refresh() {
        try {
            snapshot.set(payouts.riskSnapshot());
        } catch (RuntimeException exception) {
            meters.counter("settlement.payout.risk_metrics.refresh_failures").increment();
        }
    }
}
