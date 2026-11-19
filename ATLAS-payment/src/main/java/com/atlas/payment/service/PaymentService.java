package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentWebhookResult;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

public interface PaymentService {
    /** DB-only preparation; participates in an existing owner transaction and never calls the provider. */
    PaymentResponse prepareInitial(UUID orderId, BigDecimal amount);
    /** DB-only checkout preparation; participates in an existing owner transaction. */
    PaymentResponse prepareCheckout(UUID checkoutId, BigDecimal amount);
    
    PaymentResponse submitInitial(UUID orderId, BigDecimal amount);
    PaymentResponse completeApproval(UUID orderId, String providerOrderId);
    PaymentResponse reconcileForCancellation(UUID orderId, String idempotencyKey);
    PaymentResponse reconcile(UUID orderId, String idempotencyKey);
    PaymentResponse voidAuthorization(UUID orderId, String idempotencyKey);
    PaymentResponse find(UUID orderId);
    /** Checkout에 연결된 결제 시도를 서버 계산 금액으로 생성한다. */
    PaymentResponse submitCheckout(UUID checkoutId, BigDecimal amount);

    /** 호출자가 제공한 멱등성 키로 기존 checkout 결제 시도의 결과를 대조한다. */
    PaymentResponse reconcileCheckout(UUID checkoutId, String idempotencyKey);
    PaymentResponse completeCheckoutApproval(UUID checkoutId, String providerOrderId);
    PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey);
    /** checkout 결제 시도의 유효한 승인을 취소한다. */
    PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey);

    /** checkout의 결제 시도를 반환한다. 결제 시도가 없으면 {@code null}을 반환한다. */
    PaymentResponse findCheckout(UUID checkoutId);
    /** PayPal checkout 취소 callback의 {@code state}가 저장된 create-order 요청 ID와 일치하는지 확인한다. */
    boolean matchesCheckoutCancelState(UUID checkoutId, String state);
    PaymentResponse findByAttemptId(UUID attemptId);
    PaymentRefundResponse prepareRefundOrder(UUID orderId, String idempotencyKey, long amountWon);
    PaymentRefundResponse prepareRefundCheckout(UUID checkoutId, String idempotencyKey, long amountWon);
    /** Retries the persisted provider operation with its original provider request ID. */
    PaymentRefundResponse retryRefundOperation(UUID refundOperationId);
    /** Acknowledge owner-side application only after order/stock/settlement effects have been written. */
    void markOwnerApplied(UUID attemptId);
    void markRefundApplied(UUID refundOperationId);
    PaymentRefundResponse refundOrder(UUID orderId, String idempotencyKey, long amountWon);
    PaymentRefundResponse refundCheckout(UUID checkoutId, String idempotencyKey, long amountWon);
    PaymentRefundResponse findRefundOperation(UUID refundOperationId);
    PaymentWebhookEvent verifyPayPalWebhook(PaymentWebhookSignature signature, String rawBody);
    <T> T withPayPalWebhookOwnerLock(PaymentWebhookEvent event, Supplier<T> action);
    PaymentWebhookResult processVerifiedPayPalWebhook(PaymentWebhookEvent event);
}