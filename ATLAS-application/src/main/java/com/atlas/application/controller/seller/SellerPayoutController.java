package com.atlas.application.controller.seller;

import com.atlas.settlement.dto.response.PayoutSummaryResponse;
import com.atlas.settlement.dto.response.SellerPayoutResponse;
import com.atlas.settlement.service.SettlementPayoutService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 지급 조회·요청 API.
 *
 * <p>판매자는 자신의 지급 이력과 현재 잔액 구성을 볼 수 있어야 한다. 상태 전이(승인·송금·확정)는
 * 외부 송금 결과와 결합되는 운영 절차이므로 이 컨트롤러에 노출하지 않는다. 지급 실행 주체가
 * 정해지면 그에 맞는 엔드포인트와 권한을 별도로 추가한다.
 *
 * <p>sellerId 소유권은 기존 {@code AccountOwnershipInterceptor} 가 경로 변수 기준으로 검증한다.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/payouts")
public class SellerPayoutController {
    private final SettlementPayoutService payouts;

    @GetMapping
    public List<SellerPayoutResponse> list(@PathVariable UUID sellerId,
            @RequestParam(defaultValue = "20") int limit) {
        return payouts.findBySeller(sellerId, limit);
    }

    @GetMapping("/balance")
    public PayoutSummaryResponse balance(@PathVariable UUID sellerId) {
        return payouts.summary(sellerId);
    }

    @GetMapping("/{payoutId}")
    public SellerPayoutResponse find(@PathVariable UUID sellerId, @PathVariable UUID payoutId) {
        return payouts.findForSeller(sellerId, payoutId);
    }

    /**
     * 지급을 요청한다. 금액은 요청 본문이 아니라 원장 잔액에서 계산된다.
     *
     * <p>이유: 호출자가 금액을 정하면 잔액보다 큰 지급을 요청할 수 있다. 지급 금액은 원장이
     * 결정해야 한다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SellerPayoutResponse request(@PathVariable UUID sellerId,
            @RequestParam Instant periodStart,
            @RequestParam Instant periodEnd,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return payouts.request(sellerId, periodStart, periodEnd, idempotencyKey);
    }
}
