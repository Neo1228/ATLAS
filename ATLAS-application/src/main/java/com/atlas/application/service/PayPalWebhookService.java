package com.atlas.application.service;

import com.atlas.application.repository.PayPalWebhookRetryRepository;
import com.atlas.order.service.CheckoutPaymentService;
import com.atlas.order.service.OrderRefundService;
import com.atlas.order.service.OrderService;
import com.atlas.payment.dto.response.PaymentResponse;
import com.atlas.payment.dto.response.PaymentWebhookResponse;
import com.atlas.payment.exception.PaymentException;
import com.atlas.payment.provider.PaymentWebhookEvent;
import com.atlas.payment.provider.PaymentWebhookSignature;
import com.atlas.payment.service.PaymentService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PayPalWebhookService {
    private final PaymentService payments;
    private final OrderService orders;
    private final CheckoutPaymentService checkoutPayments;
    private final OrderRefundService refunds;
    private final PayPalWebhookRetryRepository retries;
    private final TransactionTemplate transactions;

    public PayPalWebhookService(PlatformTransactionManager transactionManager, PaymentService payments,
            OrderService orders, CheckoutPaymentService checkoutPayments, OrderRefundService refunds,
            PayPalWebhookRetryRepository retries) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.payments = Objects.requireNonNull(payments);
        this.orders = Objects.requireNonNull(orders);
        this.checkoutPayments = Objects.requireNonNull(checkoutPayments);
        this.refunds = Objects.requireNonNull(refunds);
        this.retries = Objects.requireNonNull(retries);
    }

    public void handle(PaymentWebhookSignature signature, String rawBody) {
        PaymentWebhookEvent event = payments.verifyPayPalWebhook(signature, rawBody);
        if (event == null) throw new PaymentException(PaymentException.Code.INVALID_REQUEST,
                "PayPal webhook verification returned no event");
        PaymentWebhookResponse result = processVerified(event);
        if (result.retryable()) {
            // 최소한의 재시도 레코드를 commit한 뒤에만 성공 응답을 반환한다.
            retries.enqueue(event);
        }
    }

    PaymentWebhookResponse processVerified(PaymentWebhookEvent event) {
        PaymentWebhookResponse result = payments.withPayPalWebhookOwnerLock(event,
                () -> transactions.execute(status -> {
                    PaymentWebhookResponse webhookResult = payments.processVerifiedPayPalWebhook(event);
                    if (webhookResult.duplicate() || !webhookResult.accepted()) return webhookResult;
                    if (webhookResult.refundOperationId() != null)
                        refunds.reconcilePayPalRefund(webhookResult.refundOperationId());
                    return webhookResult;
                }));
        if (result.captureConfirmed() && result.paymentAttemptId() != null) {
            PaymentResponse payment = payments.findByAttemptId(result.paymentAttemptId());
            if (payment == null) throw new PaymentException(PaymentException.Code.ATTEMPT_NOT_FOUND,
                    "Payment attempt for PayPal webhook was not found");
            if (payment.checkoutId() != null) checkoutPayments.reconcile(payment.checkoutId(), event.eventId());
            if (payment.orderId() != null) orders.reconcilePayment(payment.orderId(), event.eventId());
        }
        return result;
    }
}
