package com.atlas.settlement.service;

import com.atlas.settlement.dto.response.PayoutRiskSnapshotResponse;
import com.atlas.settlement.dto.response.PayoutSummaryResponse;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.model.SellerBalance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 판매자 지급 실행.
 *
 * <p>지급은 정산 원장의 잔액을 실제로 옮기는 단계다. 지급이 확정되면 같은 금액의 분개
 * (SELLER_PAYABLE 차변 / PSP_CLEARING 대변)를 만들어, 원장이 "지급할 돈"에서 "지급한 돈"으로
 * 넘어가게 한다. 지급 자체를 원장 밖 상태로만 두면 statement 잔액이 영원히 줄지 않는다.
 *
 * <p>상태 전이는 조건부 UPDATE 로만 한다. 두 replica 가 동시에 확정을 시도해도 하나만 성공한다.
 */
public interface SettlementPayoutService {
    /**
     * 지급을 요청한다. 금액은 호출자가 정하지 않고, 현재 지급 가능 잔액에서 계산한다.
     *
     * <p>이유: 호출자가 금액을 정하면 잔액보다 큰 지급을 요청할 수 있다. 지급 금액은
     * 원장 잔액이 결정해야 한다.
     */
    SellerPayoutResponse request(UUID sellerId, Instant periodStart, Instant periodEnd, String idempotencyKey);

    /** 지급을 승인한다. 외부 송금 전 검토 단계다. */
    SellerPayoutResponse approve(UUID payoutId);

    /**
     * Refund reservation과 공유하는 dispatch linearization fence.
     * CANCELLED가 먼저 커밋되면 provider 호출을 막고, DISPATCHING이 먼저 커밋되면
     * payout은 취소된 것으로 간주하지 않고 결과 조정 대상으로 유지한다.
     */
    boolean beginDispatch(UUID payoutId);

    SellerPayoutResponse markProviderAccepted(UUID payoutId);

    SellerPayoutResponse markProviderUnknown(UUID payoutId);

    SellerPayoutResponse markProviderUnclaimed(UUID payoutId);

    SellerPayoutResponse markProviderNeedsReview(UUID payoutId, String reasonCode);

    SellerPayoutResponse failWithProviderEvidence(UUID payoutId, String failureCode);

    /** 지급을 송금했다고 표시한다. 실제 송금 결과를 받은 뒤 호출한다. */
    SellerPayoutResponse markSent(UUID payoutId);

    /**
     * 지급을 확정하고 원장에 분개한다.
     *
     * <p>분개와 상태 전이를 같은 transaction 에서 수행한다. 둘 중 하나만 남으면 잔액과 지급
     * 이력이 어긋난다.
     */
    SellerPayoutResponse confirm(UUID payoutId);

    SellerPayoutResponse confirmProviderSuccess(UUID payoutId, long providerDebitBookValueWon, long fxRoundingExpenseWon, long providerFeeWon);

    SellerPayoutResponse fail(UUID payoutId, String failureCode);

    SellerPayoutResponse cancelBeforeDispatch(UUID payoutId, String reasonCode);

    SellerPayoutResponse find(UUID payoutId);

    /**
     * 판매자 소유 지급만 돌려준다.
     *
     * <p>소유권 검증을 controller 가 아니라 service 에 두는 이유: controller 는 HTTP binding 과
     * response mapping 만 담당하고, 존재 여부 비노출(404) 판단은 도메인 규칙이다.
     */
    SellerPayoutResponse findForSeller(UUID sellerId, UUID payoutId);

    List<SellerPayoutResponse> findBySeller(UUID sellerId, int limit);

    /** 지급 가능 잔액과 보류·수수료를 함께 돌려준다. */
    SellerBalance balance(UUID sellerId);

    /** 아직 확정되지 않은 지급과 이미 확정된 지급을 함께 돌려준다. */
    PayoutSummaryResponse summary(UUID sellerId);

    /** Global risk view for refund holds and Seller credit exposure after an in-flight payout. */
    PayoutRiskSnapshotResponse riskSnapshot();
}
