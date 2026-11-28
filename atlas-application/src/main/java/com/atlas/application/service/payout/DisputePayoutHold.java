package com.atlas.application.service.payout;

import com.atlas.order.service.OrderRefundService;
import com.atlas.settlement.dto.response.PayoutResponse;
import com.atlas.settlement.service.PayoutHoldCheck;
import com.atlas.settlement.service.SettlementPayoutService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 진행 중인 분쟁 금액만큼 판매자 몫을 남기지 못하는 송금을 보류한다(ADR-0115).
 *
 * <p>PayPal 은 분쟁이 열리면 분쟁 금액을 묶어 두고 구매자 승소면 구매자에게 돌려준다(PayPal disputes overview). 판매자
 * 패소 차감은 보류금 먼저, 그다음 지급 가능액에서 빠지므로(ADR-0052), 미확정 지급과 환불 예약을 모두 뺀 지급 가능액과
 * 보류금의 합이 분쟁 금액 이상이면 송금해도 차감할 몫이 남는다. 모자랄 때만 보류한다. 분쟁이 끝나면(판매자 승소·패소
 * 차감 모두) 보류 행이 사라져 다음 worker 시도에 송금된다.
 */
@Component
public class DisputePayoutHold implements PayoutHoldCheck {
    static final String OPEN_DISPUTE = "OPEN_DISPUTE";

    private final OrderRefundService refunds;
    private final SettlementPayoutService payouts;

    public DisputePayoutHold(OrderRefundService refunds, SettlementPayoutService payouts) {
        this.refunds = refunds;
        this.payouts = payouts;
    }

    @Override
    public Optional<String> holdReason(UUID sellerId) {
        long disputedWon = refunds.disputeHoldWon(sellerId);
        if (disputedWon <= 0) return Optional.empty();
        PayoutResponse.Summary summary = payouts.summary(sellerId);
        long coverWon = Math.addExact(summary.availablePayoutAmountWon(), Math.max(0, summary.reserveAmountWon()));
        return coverWon < disputedWon ? Optional.of(OPEN_DISPUTE) : Optional.empty();
    }
}
