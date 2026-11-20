package com.atlas.application.controller;

import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.service.SettlementPayoutService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지급 실행 상태 전이 운영자 API.
 *
 * <p>왜 필요한가: 판매자 API 는 지급을 <b>요청</b>만 할 수 있다. 승인·송금·확정·실패 전이가
 * 없으면 지급은 {@code REQUESTED} 에 영구히 머문다. 그런데 지급 가능 잔액은 아직 확정되지 않은
 * 지급을 예약으로 빼므로({@code payable = 잔액 − pending}), 그 판매자의 잔액은 <b>영원히
 * 줄어든 채로</b> 남는다. 돈이 지급 불가능한 상태로 묶인다.
 *
 * <p>왜 판매자 API 가 아닌가: 이 전이는 외부 송금 결과와 결합되는 운영 절차다. 판매자가 자기
 * 지급을 스스로 "송금 완료"로 표시할 수 있으면 안 된다. 그래서 별도 Basic 인증과 역할을 쓴다
 * ({@code PaymentRecoveryOperatorController} 와 같은 패턴).
 *
 * <p>실제 송금 주체·주기는 아직 정해지지 않았다. 이 API 는 운영자가 외부 송금을 마친 뒤
 * 결과를 기록하는 경로이며, 송금 자체를 수행하지 않는다.
 */
@RestController
@RequestMapping("/api/internal/settlement/payouts")
@ConditionalOnProperty(name = "atlas.settlement.payout.operator.enabled", havingValue = "true")
public class SettlementPayoutOperatorController {
    private final SettlementPayoutService payouts;

    public SettlementPayoutOperatorController(SettlementPayoutService payouts) {
        this.payouts = payouts;
    }

    /** REQUESTED → APPROVED. 송금 전 검토 단계. */
    @PostMapping("/{payoutId}/approve")
    public SellerPayoutResponse approve(@PathVariable UUID payoutId) {
        return payouts.approve(payoutId);
    }

    /** APPROVED → SENT. 외부 송금을 실제로 보낸 뒤 호출한다. */
    @PostMapping("/{payoutId}/sent")
    public SellerPayoutResponse markSent(@PathVariable UUID payoutId) {
        return payouts.markSent(payoutId);
    }

    /**
     * SENT → CONFIRMED. 송금 도착이 확인된 뒤 호출한다.
     *
     * <p>확정은 같은 transaction 에서 원장 분개(SELLER_PAYABLE 차변 / PSP_CLEARING 대변)를
     * 만든다. 반복 호출은 멱등이다.
     */
    @PostMapping("/{payoutId}/confirm")
    public SellerPayoutResponse confirm(@PathVariable UUID payoutId) {
        return payouts.confirm(payoutId);
    }

    /**
     * 지급을 실패로 종료한다. 확정되지 않은 지급의 잔액 예약을 해제한다.
     *
     * <p>실패 코드는 운영자가 남기는 짧은 사유다. 로그·감사에 쓰인다.
     */
    @PostMapping("/{payoutId}/fail")
    public SellerPayoutResponse fail(@PathVariable UUID payoutId,
            @RequestParam String failureCode) {
        return payouts.fail(payoutId, failureCode);
    }
}
