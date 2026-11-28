package com.atlas.order.service;

import com.atlas.order.dto.request.SellerCouponRequest;
import com.atlas.order.dto.response.CheckoutPreviewResponse;
import com.atlas.order.dto.response.CheckoutResponse;
import java.util.List;

public interface CartCheckoutService {
    CheckoutResponse checkout(String buyerRef, String idempotencyKey);
    CheckoutResponse checkout(String buyerRef, String idempotencyKey, List<SellerCouponRequest> coupons);

    /**
     * 쿠폰 없는 checkout에 지출 상한을 더한다. 합계가 상한을 넘으면 주문·재고 예약 없이
     * {@code SPENDING_CAP_EXCEEDED}로 거절한다. 같은 키의 재요청도 기존 합계로 다시 판정한다.
     */
    CheckoutResponse checkoutWithinCap(String buyerRef, String idempotencyKey, long spendingCapWon);

    /** checkout과 같은 계산으로 현재 장바구니 합계를 돌려준다. 주문과 예약을 만들지 않는다. */
    CheckoutPreviewResponse preview(String buyerRef);
}
