package com.atlas.payment.service;

import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentRefundResponse;
import com.atlas.payment.dto.response.PaymentDisputeHoldResponse;
import com.atlas.payment.dto.response.PaymentProviderAdjustmentResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.dto.response.PaymentWebhookEventResponse;
import com.atlas.payment.dto.request.PaymentWebhookSignatureRequest;
import java.math.BigDecimal;
import java.util.List;
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
    /**
     * 승인 단계까지만 진행하고 매입하지 않는다. 원격 재고 claim을 매입 전에 끝내야 하는 checkout이 사용한다.
     * 이미 승인됐으면 그대로 {@code AUTHORIZED}를 반환한다.
     */
    PaymentResponse submitCheckoutAuthorization(UUID checkoutId, BigDecimal amount);
    /** {@link #reconcileCheckout}과 같지만 {@code AUTHORIZED}에서 매입을 시작하지 않는다. */
    PaymentResponse reconcileCheckoutAuthorization(UUID checkoutId, String idempotencyKey);
    PaymentResponse completeCheckoutApproval(UUID checkoutId, String providerOrderId);
    PaymentResponse reconcileCheckoutForCancellation(UUID checkoutId, String idempotencyKey);

    /**
     * 새 checkout 캡처 예약을 막는 펜스를 저장한다. 호출자는 checkout 행을 잠그고
     * 취소 요청을 기록하는 동일한 트랜잭션에서 호출해야 한다.
     * 잠금 순서는 checkout, 결제 시도 순이며 provider 호출은 하지 않는다.
     * 이미 예약된 캡처는 계속 복구할 수 있다.
     */
    void prohibitNewCheckoutCapture(UUID checkoutId);

    /**
     * 주문이 직접 소유한 결제 시도(캠페인 주문)에 같은 캡처 펜스를 저장한다. 호출자는 주문 행을
     * 잠그고 취소 요청을 기록하는 동일한 트랜잭션에서 호출해야 하며, 잠금 순서는 주문, 결제 시도 순이다.
     */
    void prohibitNewOrderCapture(UUID orderId);
    /** checkout 결제 시도의 유효한 승인을 취소한다. */
    PaymentResponse voidCheckoutAuthorization(UUID checkoutId, String idempotencyKey);
    /**
     * 승인 deadline이 지난 결제 시도를 실제 provider 상태로 확정한다.
     *
     * <p>이미 승인/청구/취소가 확정됐으면 그 사실을 그대로 반영하고, 아직 승인 전이면
     * provider order를 void한다. 재고 해제는 owner 적용 단계에서만 일어난다.
     */
    PaymentResponse resolveExpiredApproval(UUID attemptId, String idempotencyKey);

    /** checkout의 결제 시도를 반환한다. 결제 시도가 없으면 {@code null}을 반환한다. */
    PaymentResponse findCheckout(UUID checkoutId);

    /**
     * 아직 provider 에 보내지 않은(NOT_STARTED) checkout 결제 시도를 종결한다.
     *
     * <p>시도 행을 잠그고 NOT_STARTED 일 때만 VOIDED 로 바꾼다. 결제 단계 예약({@code reservePhase})도
     * 같은 행을 잠그고 terminal 이면 provider 를 부르지 않으므로, 이 호출이 성공하면 이후 provider
     * 호출이 일어나지 않는다는 펜스가 된다. 시도가 없으면 provider 작업도 없으므로 {@code true}.
     *
     * @return 종결했거나 시도가 없으면 {@code true}; 이미 provider 단계로 넘어갔으면 {@code false}
     */
    boolean abandonUnstartedCheckout(UUID checkoutId);
    /** PayPal checkout 취소 callback의 {@code state}가 저장된 create-order 요청 ID와 일치하는지 확인한다. */
    boolean matchesCheckoutCancelToken(UUID checkoutId, String cancelToken);
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
    PaymentWebhookEventResponse verifyPayPalWebhook(PaymentWebhookSignatureRequest signature, String rawBody);
    <T> T withPayPalWebhookOwnerLock(PaymentWebhookEventResponse event, Supplier<T> action);
    PaymentWebhookResponse processVerifiedPayPalWebhook(PaymentWebhookEventResponse event);
    /** PayPal 쪽에서 시작된 환불·역전·분쟁 기록(ADR-0111). 원장 반영은 주문 계층이 한다. */
    PaymentProviderAdjustmentResponse findProviderAdjustment(UUID adjustmentId);
    /** 판매자 송금을 보류해야 하는 진행 중 분쟁(ADR-0115). */
    List<PaymentDisputeHoldResponse> heldDisputes();
    /** 판매자 원장 차감을 남긴 뒤에만 부른다. */
    void markProviderAdjustmentPosted(UUID adjustmentId);
    /** 자동으로 판매자에게 귀속할 수 없을 때 돈을 움직이지 않고 운영자 검토로 넘긴다. */
    void markProviderAdjustmentForReview(UUID adjustmentId, String reason);
}