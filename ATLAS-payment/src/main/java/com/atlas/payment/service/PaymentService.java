package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import java.util.UUID;

public interface PaymentService {

    PaymentResponse submitInitial(UUID orderId);

    PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey);
    PaymentResponse reconcile(UUID orderId, String idempotencyKey);

    PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey);
    PaymentResponse find(UUID orderId);

    /** 구매 checkout에 속한 고유 결제 시도를 생성하거나 이어서 처리한다. */
    PaymentResponse submitCheckout(UUID checkoutId);

    /** 호출자가 제공한 멱등성 키로 기존 checkout 결제 시도의 결과를 대조한다. */
    PaymentResponse reconcileCheckout(UUID checkoutId, String idempotencyKey);

    PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey);

    /** checkout 결제 시도의 유효한 승인을 취소한다. */
    PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey);


    /** checkout의 결제 시도를 반환한다. 결제 시도가 없으면 {@code null}을 반환한다. */

    PaymentResponse findCheckout(UUID checkoutId);
}
